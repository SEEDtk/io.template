/**
 *
 */
package org.theseed.json;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonArray;
import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonObject;
import com.github.cliftonlabs.json_simple.Jsoner;

/**
 * This class processes a JSON file, modifying identifiers for the features and genomes.  The base
 * class provides a hook for preprocessing and one for conversion.  In this hook, the subclasses
 * can handle setting up the current genome, initializing patric IDs, and computing feature ID mappings.
 * The remainder will update the file's JSON array in place and then store it back.
 *
 * This single class supports both the addition of new identifiers (magic words) or the translation
 * of identifiers due to combining multiple genomes into a single one.
 *
 * We recognize three basic types of keys.  If a genome ID is found, we add a second key whose value is
 * the corresponding genome word.  If a feature ID is found, we add a second key whose value is the
 * corresponding feature word.  If a phrase key is found, we replace feature IDs in the text with
 * feature words.
 *
 * @author Bruce Parrello
 *
 */
public abstract class JsonConverter {

    // FIELDS
    /** logging facility */
    private static final Logger log = LoggerFactory.getLogger(JsonConverter.class);
    /** feature ID mapper */
    private FidMapper fidMapper;
    /** JSON file name */
    private File jsonFile;
    /** main JSON array */
    private JsonArray jsonList;
    /** number of new fields added */
    private int newFieldCount;
    /** number of fields changed */
    private int changeFieldCount;
    /** key storage method */
    private KeyMode keyMode;
    /** match pattern for genome ID keys */
    public static final Pattern GENOME_KEY_PATTERN = Pattern.compile("genome_id(_[a-zA-Z0-9])?");
    /** match pattern for feature ID keys */
    public static final Pattern FEATURE_KEY_PATTERN = Pattern.compile("(patric_id|interactor)(_[a-zA-Z0-9])?");
    /** match pattern for phrase keys */
    public static final Pattern PHRASE_KEY_PATTERN = Pattern.compile("(gene_rule)");
    /** match pattern for feature IDs themselves */
    private static final Pattern FEATURE_ID_PATTERN = Pattern.compile("\\bfig\\|\\d+\\.\\d+\\.[^.]+\\.\\d+\\b");

    /**
     * This enum determines how a new value is stored in the case of a new genome or feature ID key in the
     * json.
     */
    public static enum KeyMode {
    	/** store the new ID over the old one */
    	USE_OLD_KEY {
			@Override
			public void putNewId(String key, Map<String, Object> newFields, Object newId, String newName,
					String suffix) {
				// Simply use the old key value with the new ID.
				newFields.put(key, newId);
			}
		},
    	/** add an alternate key using "word" for the new ID */
    	USE_WORD_KEY {
			@Override
			public void putNewId(String key, Map<String, Object> newFields, Object newId, String newName,
					String suffix) {
		        // Compute the full name for the new mapping.
				String wordName = newName + "_word";
		        String fullName = (suffix != null ? wordName + suffix : wordName);
		        newFields.put(fullName, newId);
			}
		};

        /**
         * Store a new ID mapping in the new-fields map.
         *
         * @param key			original key
         * @param newFields		new-fields map to receive the ID mapping
         * @param newId			ID to store
         * @param newName		type of the new key ("genome" or "feature")
         * @param suffix		optional suffix for the new key
         */
        public abstract void putNewId(String key, Map<String, Object> newFields, Object newId, String newName, String suffix);
    }

    /**
     * Construct a JSON converter for a specified file.
     *
     * @param fidMapper		feature ID mapper to use
     * @param dir			directory containing the file
     * @param fileName		base name of the file
     * @param mode			key storage mode to use
     *
     * @throws IOException
     * @throws JsonException
     */
    public JsonConverter(FidMapper fidMap, File dir, String fileName, KeyMode mode) throws IOException, JsonException {
        // Save the feature ID mapper.
        this.fidMapper = fidMap;
        // Save the key-storage mode.
        this.keyMode = mode;
        // Compute the file name.
        this.jsonFile = new File(dir, fileName);
        // Load the file into memory.
        this.jsonList = readJson(this.jsonFile);
        log.info("Processing {} file {} with {} records.", dir, fileName, this.jsonList.size());
        // Denote there have been no modifications.
        this.newFieldCount = 0;
        this.changeFieldCount = 0;
    }

	/**
	 * Read a JSON file into an array.
	 *
	 * @param inFile	input JSON file to read
	 *
	 * @throws JsonException
	 * @throws IOException
	 */
	public static JsonArray readJson(File inFile) throws JsonException, IOException {
		JsonArray retVal = null;
		log.info("Reading JSON file {}.", inFile);
        try (FileReader reader = new FileReader(inFile)) {
            retVal = (JsonArray) Jsoner.deserialize(reader);
        }
        return retVal;
	}

    /**
     * @return the current JSON file name
     */
    public File getFile() {
        return this.jsonFile;
    }

    /**
     * Process each object in the json list.  For each, we call the subclass's pre-process method,
     * then accumulate new fields to add.
     */
    public void process() {
        long lastMessage = System.currentTimeMillis();
        int recordCount = 0;
        int gNotFoundCount = 0;
        int fNotFoundCount = 0;
        // Loop through the records.
        for (Object entry : this.jsonList) {
            recordCount++;
            JsonObject record = (JsonObject) entry;
            this.preProcessRecord(record);
            // Now process each field, accumulating new ones.
            Map<String, Object> newFields = new TreeMap<String, Object>();
            for (var fieldEntry : record.entrySet()) {
                String key = fieldEntry.getKey();
                Matcher m = GENOME_KEY_PATTERN.matcher(key);
                if (m.matches()) {
                    Object value = fieldEntry.getValue();
                    // Here we must check for the special case that the value is an array.
                    if (value instanceof JsonArray) {
                        // We will build a new array of genome word IDs, but if any of the genome IDs
                        // are missing, we skip the whole process.
                        JsonArray newValues = new JsonArray();
                        boolean ok = true;
                        Iterator<Object> iter = ((JsonArray) value).iterator();
                        while (iter.hasNext() && ok) {
                            String oldId = iter.next().toString();
                            String newId = this.getFidMapper().getNewGenomeId(oldId);
                            if (newId == null)
                                ok = false;
                            else
                                newValues.add(newId);
                        }
                        if (ok) {
                            this.putNewId(key, newFields, newValues, "genome", m.group(1));
                        }
                    } else {
                        String oldId = value.toString();
                        String newId = this.getFidMapper().getNewGenomeId(oldId);
                        if (newId == null) {
                            // Here we don't have an ID for this genome.  Count it as an error.
                            gNotFoundCount++;
                        } else {
                            this.putNewId(key, newFields, newId, "genome", m.group(1));
                            this.newFieldCount++;
                        }
                    }
                } else {
                    // Not a genome field.  Check for a feature field.
                    m = FEATURE_KEY_PATTERN.matcher(key);
                    if (m.matches()) {
                        String oldId = fieldEntry.getValue().toString();
                        String newId = this.getFidMapper().getNewFid(oldId);
                        if (newId == null)
                            fNotFoundCount++;
                        else {
                            this.putNewId(key, newFields, newId, "feature", m.group(2));
                            this.newFieldCount++;
                        }
                    } else {
                        // Finally, check for a phrase key.  In a phrase key, we replace feature IDs found
                        // in the value.
                        m = PHRASE_KEY_PATTERN.matcher(key);
                        if (m.matches()) {
                            String oldValue = fieldEntry.getValue().toString();
                            StringBuilder newValue = new StringBuilder(oldValue.length() * 2);
                            // Here we find all the occurrences of feature IDs.
                            Matcher vm = FEATURE_ID_PATTERN.matcher(oldValue);
                            int currPos = 0;
                            while (vm.find()) {
                                // Get the section before the current match.
                                newValue.append(StringUtils.substring(oldValue, currPos, vm.start()));
                                // Replace the current match with the appropriate feature word.
                                String fid = vm.group();
                                String newFid = this.fidMapper.getNewFid(fid);
                                if (newFid == null) {
                                    newFid = fid;
                                    fNotFoundCount++;
                                }
                                newValue.append(newFid);
                                // Position after the current match.
                                currPos = vm.end();
                            }
                            // Copy the residual into the output buffer and store it.
                            newValue.append(StringUtils.substring(oldValue, currPos));
                            newFields.put(key, newValue.toString());
                            this.changeFieldCount++;
                        }
                    }
                }
            }
            // We have all the new fields.  Add them here.
            if (! newFields.isEmpty())
                record.putAll(newFields);
            long nowTime = System.currentTimeMillis();
            if (nowTime - lastMessage >= 5000) {
                log.info("{} records scanned.  {} fields added and {} changed.  {} genomes and {} features were not found.",
                        recordCount, this.newFieldCount, this.changeFieldCount, gNotFoundCount, fNotFoundCount);
                lastMessage = nowTime;
            }
        }
        log.info("{} total records scanned in {}.  {} fields added and {} changed.  {} genomes and {} features were not found.",
                recordCount, this.jsonFile, this.newFieldCount, this.changeFieldCount, gNotFoundCount, fNotFoundCount);
    }

    /**
     * Pre-process the record to prepare for the main processing.
     *
     * @param record	record to pre-process
     */
    protected abstract void preProcessRecord(JsonObject record);

    /**
     * Store a new ID mapping in the new-fields map.
     *
     * @param key			original key
     * @param newFields		new-fields map to receive the ID mapping
     * @param newId			ID to store
     * @param newName		base name for the new key
     * @param suffix		optional suffix for the new key
     */
    private void putNewId(String key, Map<String, Object> newFields, Object newId, String newName, String suffix) {
    	this.keyMode.putNewId(key, newFields, newId, newName, suffix);
    }

    /**
     * Save the JSON data back to the file.
     * @param targetFile
     *
     * @throws IOException
     */
    public void save(File targetFile) throws IOException {
        log.info("Saving {} to {} with {} new fields.", this.jsonFile, targetFile, this.newFieldCount);
        // Because of the large file sizes, we have to save the JSON one record at a time.
        try (PrintWriter jsonWriter = new PrintWriter(targetFile)) {
            jsonWriter.println("[");
            JsonArray recordList = this.jsonList;
            int rCount = writeJsonRecords(targetFile, jsonWriter, recordList, 0);
            jsonWriter.println("\n]");
            log.info("{} total records written to {}.", rCount, targetFile);
        }
    }

	/**
	 * This is a utility method to write JSON records to an open output file. The framework
	 * not only opens the file, but must handle the start and end brackets. Provision is made
	 * to handle output record counting.
	 *
	 * @param targetFile	output file name (for tracing)
	 * @param jsonWriter	output print writer
	 * @param recordList	list of records to write
	 * @param rCount		number of records already written to the output
	 *
	 * @return the updated output record count
	 */
	public static int writeJsonRecords(File targetFile, PrintWriter jsonWriter, JsonArray recordList, int rCount) {
		long lastMsg = System.currentTimeMillis();
		for (var obj : recordList) {
		    rCount++;
		    String objString = Jsoner.prettyPrint(Jsoner.serialize(obj));
		    if (rCount > 1)
		        jsonWriter.println(",");
		    jsonWriter.print(objString);
		    long nowTime = System.currentTimeMillis();
		    if (nowTime - lastMsg >= 5000) {
		        log.info("{} records written to {}.", rCount, targetFile);
		        lastMsg = nowTime;
		    }
		}
		return rCount;
	}

    /**
     * @return the fidMapper
     */
    public FidMapper getFidMapper() {
        return fidMapper;
    }

    /**
     * @return the list of updated JSON records
     */
    public JsonArray getJsonList() {
    	return this.jsonList;
    }

}
