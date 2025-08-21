/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.TabbedInputStream;
import org.theseed.json.JsonConverter;
import org.theseed.json.JsonConverter.KeyMode;
import org.theseed.magic.CombinationFidMapper;
import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonArray;
import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonKey;
import com.github.cliftonlabs.json_simple.JsonObject;
import com.github.cliftonlabs.json_simple.Jsoner;

/**
 * This command combines multiple genomes in genome JSON dump directories into a single genome. This
 * requires extensive renumbering of feature IDs and massive updates to the genome.json file.
 *
 * The standard input should contain a tab-delimited file, with headers, indicating which genomes get
 * combined into which other genomes. The source genome should be in the first column and the target
 * genome in the second column. If a genome is its own target or is not being combined, it does not
 * need to be listed.
 *
 * The positional parameters are the name of the primary genome dump master directory, and then the
 * names of the other dump directories. Each dump directory contains subdirectories with the json
 * files.  All the json files are processed and then copied into new directories. The new directories
 * will contain the combined files.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -D	output directory (default is "Updated" in the current directory)
 * -i	input file containing mappings (if not STDIN)
 *
 * --clear		if specified, the output directory will be erased before processing
 * --sourceCol	name of source-genome-ID column (default "source_id")
 * --targetCol	name of target-genome-ID column (default "target_id")
 *
 * @author Bruce Parrello
 *
 */
public class CombineProcessor extends BaseJsonUpdateProcessor {

	// FIELDS
	/** logging facility */
	private static final Logger log = LoggerFactory.getLogger(CombineProcessor.class);
	/** ID mapper */
	private CombinationFidMapper fidMapper;
	/** genome ID map (source -> target) */
	private Map<String, String> genomeMap;
	/** JSON key object for integer fetch */
	private IntJsonKey intKey;
	/** list of genome keys that are totals */
	private static final Set<String> GENOME_TOTALS = Set.of("partial_cds", "trna", "contigs", "chromosomes",
			"cds", "rrna", "plasmids", "hypothetical_cds", "patric_cds", "plfam_cds");
	/** CDS ratio types */
	private static final Set<String> RATIO_TYPES = Set.of("partial", "hypothetical", "plfam");
	/** JSON key for GC content percent */
	private static final JsonKey GC_KEY = new GcContentKey();
	/** JSON key for genome name */
	private static final JsonKey NAME_KEY = new GenomeNameKey();

	// COMMAND-LINE OPTIONS

	/** input file name (if not STDIN) */
	@Option(name = "--input", aliases = { "-i" }, usage = "input file name (if not STDIN)")
	private File inFile;

	/** column spec for source genome IDs */
	@Option(name = "--sourceCol", metaVar = "source_id", usage = "index (1-based) or name of source-genome-ID column")
	private String sourceCol;

	/** column spec for target genome IDs */
	@Option(name = "--targetCol", metaVar = "target_id", usage = "index (1-based) or name of targeet-genome-ID column")
	private String targetCol;

	/**
	 * This is a utility class used to form integer JSON keys with a 0 default.
	 */
	protected static class IntJsonKey implements JsonKey {

		/** key name */
		private String keyName;

		/**
		 * Construct a blank integer JSON key.
		 */
		public IntJsonKey() {
			this.keyName = "none";
		}

		@Override
		public String getKey() {
			return this.keyName;
		}

		/**
		 * Specify a new key name.
		 *
		 * @param newKey	desired new key name
		 *
		 * @return this object
		 */
		public JsonKey setKey(String newKey) {
			this.keyName = newKey;
			return this;
		}

		@Override
		public Object getValue() {
			// The default is always 0.
			return (Integer) 0;
		}

	}

	/**
	 * This is a utility class for the GC content key.
	 */
	protected static class GcContentKey implements JsonKey {

		@Override
		public String getKey() {
			return "gc_content";
		}

		@Override
		public Object getValue() {
			return (Double) 0.0;
		}

	}

	/**
	 * This is a utility class for the genome name key.
	 */
	protected static class GenomeNameKey implements JsonKey {

		@Override
		public String getKey() {
			return "genome_name";
		}

		@Override
		public Object getValue() {
			return "unknown genome";
		}

	}

	@Override
	protected KeyMode getKeyMode() {
		return JsonConverter.KeyMode.USE_OLD_KEY;
	}

	@Override
	protected void setJsonUpdateDefaults() {
		this.inFile = null;
		this.sourceCol = "source_id";
		this.targetCol = "target_id";
		// Insure we have the JSON integer key buffer.
		this.intKey = new IntJsonKey();
	}

	@Override
	protected void validateJsonUpdateParms() throws IOException, ParseFailureException {
		// Our main goal is to read in the genome mappings.
		try (TabbedInputStream inStream = this.getInputStream()) {
			// Compute the input column indices.
			int sourceIdx = inStream.findField(this.sourceCol);
			int targetIdx = inStream.findField(this.targetCol);
			// Read the mappings from the file.
			this.genomeMap = new HashMap<String, String>();
			for (var line : inStream)
				this.genomeMap.put(line.get(sourceIdx), line.get(targetIdx));
			log.info("{} genome mappings read from input.", this.genomeMap.size());
		}
	}

	/**
	 * @return the input stream indicated by the parameters
	 *
	 * @throws IOException
	 */
	private TabbedInputStream getInputStream() throws IOException {
		TabbedInputStream retVal;
		if (this.inFile == null) {
			log.info("Genome mappings will be read from the standard input.");
			retVal = new TabbedInputStream(System.in);
		} else if (! this.inFile.canRead())
			throw new FileNotFoundException("Input file " + this.inFile + " is not found or invalid.");
		else {
			log.info("Genome mappings will be read from {}.", this.inFile);
			retVal = new TabbedInputStream(this.inFile);
		}
		return retVal;
	}

	@Override
	protected FidMapper getFidMapper() {
		// Create the ID-mapper and save a reference to it.
		this.fidMapper = new CombinationFidMapper(this.genomeMap);
		return this.fidMapper;
	}

	@Override
	protected void convertGenome(FidMapper mapper, File genomeDir, File masterDir) throws IOException, JsonException {
		// The genome file is special. We don't need to update any IDs, but we need to accumulate the various totals
		// and counters if we've seen the target genome before. First, we get the input genome's data.
		File inFile = new File(genomeDir, "genome.json");
		JsonArray genomeList = JsonConverter.readJson(inFile);
		// There is always only one record-- the genome itself.
		JsonObject genomeJson = (JsonObject) genomeList.get(0);
		// Now we compute the output file.
		File outFile = this.getOutputFile(inFile);
		// Get the old genome ID and set up the new genome.
		String oldGenomeId  = genomeDir.getName();
		this.fidMapper.setup(oldGenomeId, genomeJson.getStringOrDefault(NAME_KEY));
		// Get the new genome ID.
		String newGenomeId = mapper.getNewGenomeId(oldGenomeId);
		// If the output file does not exist, we simply write the JSON to the output with the ID updated.
		if (! outFile.exists()) {
			// Replace the old genome ID with the new genome ID.
			genomeJson.put("genome_id", newGenomeId);
			// Delete the contig L50 and N50.
			genomeJson.remove("contig_l50");
			genomeJson.remove("contig_n50");
		} else {
			log.info("Updating {} with data from {}.", oldGenomeId, newGenomeId);
			// Here we have to add the totals in the input file to the totals in the existing genome file.\
			// and recompute the contig stats.
			genomeList = JsonConverter.readJson(outFile);
			JsonObject prevGenomeJson = (JsonObject) genomeList.get(0);
			// Update the GC content percentage.
			double old_gc_percent = prevGenomeJson.getDoubleOrDefault(GC_KEY);
			double new_gc_percent = genomeJson.getDoubleOrDefault(GC_KEY);
			this.intKey.setKey("genome_length");
			int old_length = prevGenomeJson.getIntegerOrDefault(this.intKey);
			int new_length = genomeJson.getIntegerOrDefault(this.intKey);
			int total_length = old_length + new_length;
			double gc_percent = 0.0;
			if (total_length > 0)
				gc_percent = (old_gc_percent * old_length + new_gc_percent * new_length) / total_length;
			prevGenomeJson.put(GC_KEY.getKey(), gc_percent);
			// Loop through the numeric keys, accumulating totals in the old genome.
			for (String totalKey : GENOME_TOTALS) {
				this.intKey.setKey(totalKey);
				int total = prevGenomeJson.getIntegerOrDefault(this.intKey);
				total += genomeJson.getIntegerOrDefault(this.intKey);
				prevGenomeJson.put(totalKey, total);
			}
			// Now we need to update the ratios. The denominator for the ratios is the CDS total.
			// Note the corrected counts are in the OLD genome, which is the one we're writing
			// back.
			double cd_count = (double) prevGenomeJson.getIntegerOrDefault(this.intKey.setKey("cds"));
			for (String ratioName : RATIO_TYPES) {
				double ratio = 0.0;
				if (cd_count > 0) {
					int numerator = prevGenomeJson.getIntegerOrDefault(this.intKey.setKey(ratioName + "_cds"));
					ratio = numerator / cd_count;
				}
				prevGenomeJson.put(ratioName + "_cds_ratio", ratio);
			}
			// The last ratio is CDS_RATIO, where the denominator is the genome size.
			double ratio = 0.0;
			if (total_length > 0)
				ratio = prevGenomeJson.getIntegerOrDefault(this.intKey.setKey("cds")) * 1000.0 / total_length;
			prevGenomeJson.put("cds_ratio", ratio);
		}
		// Write the genome record to the output file.
		log.info("Writing {} to {}.", newGenomeId, outFile);
		try (PrintWriter jsonWriter = new PrintWriter(outFile)) {
			Jsoner.serialize(genomeList, jsonWriter);
		}
	}

	@Override
	protected void processConverter(JsonConverter converter, File targetFile) throws IOException, JsonException {
		// Update the JSON.
		converter.process();
		// Now we need to save the updated JSON. We have to do this one record at a time. First we
		// output the old records (if any), then the new ones. Note we have to read in the old records
		// BEFORE we open the output file.
		JsonArray currentJson;
		if (targetFile.exists())
			currentJson = JsonConverter.readJson(targetFile);
		else
			currentJson = null;
		try (PrintWriter outStream = new PrintWriter(targetFile)) {
			outStream.println("[");
			int rCount = 0;
			if (currentJson != null) {
				// Here we have old records to write.
				log.info("Rewriting original records from {}.", targetFile);
				rCount = JsonConverter.writeJsonRecords(targetFile, outStream, currentJson, rCount);
			}
			// Now write out the new records.
			currentJson = converter.getJsonList();
			log.info("Writing updated records from {}.", converter.getFile());
			rCount = JsonConverter.writeJsonRecords(targetFile, outStream, currentJson, rCount);
			outStream.println("\n]");
			log.info("{} total records written to {}.", rCount, targetFile);
		}
	}

	@Override
	protected File getOutputFile(File jsonFile) {
		// Get the input file's name.
		String fileName = jsonFile.getName();
		// The genome ID is the parent directory name.
		File parent = jsonFile.getParentFile();
		String genomeId = parent.getName();
		// Get the master subdirectory.
		File grandParent = parent.getParentFile();
		String master = grandParent.getName();
		// Compute the new genome Id.
		String newGenomeId = this.genomeMap.getOrDefault(genomeId, genomeId);
		// Construct the new output file name.
		File masterDir = this.getOutputDir();
		grandParent = new File(masterDir, master);
		parent = new File(grandParent, newGenomeId);
		File retVal = new File(parent, fileName);
		// Verify that the parent directory exists.
		try {
			FileUtils.forceMkdirParent(retVal);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return retVal;
	}

}
