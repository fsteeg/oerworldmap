/* Copyright 2014 Fabian Steeg, hbz. Licensed under the Eclipse Public License 1.0 */

package controllers.oer;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;

import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.index.IndexRequestBuilder;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.client.IndicesAdminClient;

import play.Play;
import play.libs.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.jsonldjava.core.JsonLdError;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import com.github.jsonldjava.utils.JSONUtils;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;

/**
 * Convert N-Triples to JSON-LD and index it in Elasticsearch.
 * 
 * @author Fabian Steeg (fsteeg)
 */
public class NtToEs {

	static final String CONFIG = "public/data/index-config.json";
	static final String CONTEXT = "public/data/context.json";
	static final String TYPE = Application.DATA_TYPE;
	static final String INDEX = Application.DATA_INDEX;
	static final Map<String, String> idMap = new HashMap<String, String>();

	public static void main(String... args) throws ElasticsearchException,
			FileNotFoundException, IOException {
		if (args.length != 1 && args.length != 2) {
			System.out.println("Pass the root directory to crawl. "
					+ "Will recursively gather all content from *.nt "
					+ "files with identical names, convert these to "
					+ "compact JSON-LD and index it in ES. "
					+ "If a second argument is passed it is processed "
					+ "as a TSV file that maps file names (w/o "
					+ "extensions) to IDs to be used for ES. Else the "
					+ "file names (w/o extensions) are used.");
			args = new String[] { "../oer-data/tmp",
					"../oer-data/src/main/resources/internalId2uuid.tsv" };
			System.out.println("Using defaults: " + Arrays.asList(args));
		}
		if (args.length == 2)
			initMap(args[1]);
		TripleCrawler crawler = new TripleCrawler();
		Files.walkFileTree(Paths.get(args[0]), crawler);
		createIndex(config(), INDEX);
		process(crawler.data);
	}

	static String config() throws IOException, FileNotFoundException {
		return CharStreams.toString(new FileReader(CONFIG));
	}

	private static final class TripleCrawler extends SimpleFileVisitor<Path> {
		Map<String, StringBuilder> data = new HashMap<String, StringBuilder>();

		@Override
		public FileVisitResult visitFile(Path path, BasicFileAttributes attr)
				throws IOException {
			if (path.toString().endsWith(".nt")) {
				String content = com.google.common.io.Files.toString(
						path.toFile(), Charsets.UTF_8);
				String key = path.getFileName().toString();
				collectContent(uuidForFileName(key), content);
			}
			return FileVisitResult.CONTINUE;
		}

		private void collectContent(String name, String content) {
			if (!data.containsKey(name))
				data.put(name, new StringBuilder());
			data.get(name).append("\n").append(content);
		}
	}

	static void createIndex(String config, String index) {
		IndicesAdminClient admin = Application.client.admin().indices();
		if (!admin.prepareExists(index).execute().actionGet().isExists()) {
			System.err.println("Creating index with config:\n" + config);
			admin.prepareCreate(index).setSource(config).execute().actionGet();
		}
	}

	private static void process(Map<String, StringBuilder> map) {
		for (Entry<String, StringBuilder> e : map.entrySet()) {
			String mapKey = e.getKey();
			try {
				String jsonLd = rdfToJsonLd(e.getValue().toString(),
						Lang.NTRIPLES);
				String parent = findParent(jsonLd);
				String key = uuidForFileName(mapKey);
				indexData(key, jsonLd, INDEX, TYPE, parent);
			} catch (Exception x) {
				System.err.printf("Could not process file %s due to %s\n",
						mapKey, x.getMessage());
				x.printStackTrace();
			}
		}
	}

	private static String uuidForFileName(String fileName) {
		String id = fileName.split("\\.")[0];
		return idMap.isEmpty() || idMap.get(id) == null ? id : idMap.get(id);
	}

	static String findParent(String jsonLd) {
		JsonNode value = Json.parse(jsonLd).findValue("addressCountry");
		return value != null && value.isArray() /* else in context */
		? value.get(0).asText().trim() : "http://sws.geonames.org/";
	}

	private static void initMap(String mapFile) {
		try (Scanner s = new Scanner(new File(mapFile))) {
			while (s.hasNextLine()) {
				String[] keyVal = s.nextLine().split("\\s");
				idMap.put(keyVal[0].trim(), keyVal[1].trim());
			}
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
	}

	static void indexData(String id, String data, String index, String type,
			String parent) {
		IndexRequestBuilder builder = Application.client.prepareIndex(index,
				type, id).setSource(data);
		if (parent != null)
			builder = builder.setParent(parent);
		IndexResponse r = builder.execute().actionGet();
		System.out.printf(
				"Indexed into index %s, type %s, id %s, version %s: %s\n",
				r.getIndex(), r.getType(), r.getId(), r.getVersion(), data);
	}

	static String rdfToJsonLd(String data, Lang lang) {
		StringWriter stringWriter = new StringWriter();
		InputStream in = new ByteArrayInputStream(data.getBytes(Charsets.UTF_8));
		Model model = ModelFactory.createDefaultModel();
		RDFDataMgr.read(model, in, lang);
		RDFDataMgr.write(stringWriter, model, Lang.JSONLD);
		return compact(stringWriter.toString());
	}

	static String jsonLdToRdf(JsonNode data, Lang lang) {
		StringWriter stringWriter = new StringWriter();
		Model model = ModelFactory.createDefaultModel();
		for (JsonNode jsonNode : data) {
			InputStream in = new ByteArrayInputStream(jsonNode.toString()
					.getBytes(Charsets.UTF_8));
			RDFDataMgr.read(model, in, Lang.JSONLD);
		}
		RDFDataMgr.write(stringWriter, model, lang);
		return stringWriter.toString();
	}

	static String compact(String json) {
		try {
			Object contextJson = JSONUtils.fromURL(context());
			JsonLdOptions options = new JsonLdOptions();
			options.setCompactArrays(false); // ES needs consistent data
			Map<String, Object> compact = JsonLdProcessor.compact(
					JSONUtils.fromString(json), contextJson, options);
			return withGeo(compact);
		} catch (IOException | JsonLdError e) {
			throw new IllegalStateException("Could not compact JSON-LD: \n"
					+ json, e);
		}
	}

	private static String withGeo(Map<String, Object> compact) {
		JsonNode json = Json.parse(JSONUtils.toString(compact));
		JsonNode lat = json.findValue("latitude");
		JsonNode lon = json.findValue("longitude");
		if (lat != null && lon != null && lat instanceof ArrayNode
				&& lon instanceof ArrayNode)
			compact.put("location", ((ArrayNode) lat).get(0).textValue() + ", "
					+ ((ArrayNode) lon).get(0).textValue());
		System.out.println(JSONUtils.toPrettyString(compact));
		return JSONUtils.toString(compact);
	}

	public static URL context() throws MalformedURLException {
		final String path = "public/data";
		final String file = "context.json";
		if (new File(path, file).exists())
			return new File(path, file).toURI().toURL();
		return Play.application().resource("/" + path + "/" + file);
	}
}
