/**
 *
 */
package org.theseed.json;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonArray;
import com.github.cliftonlabs.json_simple.JsonObject;
import com.github.cliftonlabs.json_simple.Jsoner;

/**
 * This method processes a JSON file, adding magic word IDs for the features and genomes.  The base
 * class provides a hook for preprocessing.  In this hook, the subclasses can handle setting up the
 * current genome, initializing patric IDs, and computing feature ID mappings.  The remainder will
 * update the file's JSON array in place and then store it back.
 *
 * @author Bruce Parrello
 *
 */
public abstract class JsonConverter {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(JsonConverter.class);
    /** feature ID mapper */
    private FidMapper fidMapper;
    /** JSON file name */
    private File jsonFile;
    /** main JSON array */
    private JsonArray jsonList;
    /** number of new fields added */
    private int newFieldCount;
    /** match pattern for genome IDs */
    public static final Pattern GENOME_ID_PATTERN = Pattern.compile("\"genome_id(_[a-zA-Z0-9])?\"");
    /** match pattern for feature IDs */
    public static final Pattern FEATURE_ID_PATTERN = Pattern.compile("\"(patric|feature)_id(_[a-zA-Z0-9])?\"");
    /** empty json array */
    private static final JsonArray EMPTY_ARRAY = new JsonArray();

    /**
     * Construct a JSON converter for a specified file.
     *
     * @param fidMapper		feature ID mapper to use
     * @param dir			directory containing the file
     * @param fileName		base name of the file
     *
     * @throws IOException
     */
    public JsonConverter(FidMapper fidMap, File dir, String fileName) throws IOException {
        // Save the feature ID mapper.
        this.fidMapper = fidMap;
        // Compute the file name.
        this.jsonFile = new File(dir, fileName);
        // Load the file into memory.
        String jsonString = FileUtils.readFileToString(this.jsonFile, Charset.defaultCharset());
        // Convert it to a Json array.
        this.jsonList = Jsoner.deserialize(jsonString, EMPTY_ARRAY);
        log.info("Processing {} file {} with {} records.", dir, fileName, this.jsonList.size());
        // Denote there have been no modifications.
        this.newFieldCount = 0;
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
            JsonObject record = (JsonObject) entry;
            this.preProcessRecord(record);
            // Now process each field, accumulating new ones.
            Map<String, String> newFields = new TreeMap<String, String>();
            for (var fieldEntry : record.entrySet()) {
                String key = fieldEntry.getKey();
                Matcher m = GENOME_ID_PATTERN.matcher(key);
                if (m.matches()) {
                    String oldId = fieldEntry.getValue().toString();
                    String newId = this.getFidMapper().getMagicGenomeId(oldId);
                    if (newId == null) {
                        // Here we don't have an ID for this genome.  Count it as an error.
                        gNotFoundCount++;
                    } else {
                        this.putNewId(newFields, newId, "genome_word", m.group(1));
                        this.newFieldCount++;
                    }
                } else {
                    // Not a genome field.  Check for a feature field.
                    m = FEATURE_ID_PATTERN.matcher(key);
                    if (m.matches()) {
                        String oldId = fieldEntry.getValue().toString();
                        String newId = this.getFidMapper().getMagicFid(oldId);
                        if (newId == null) {
                            // The ID was not found.  This is only a problem for patric IDs.
                            if (m.group(1).contentEquals("patric"))
                                fNotFoundCount++;
                        } else {
                            this.putNewId(newFields, newId, "feature_word", m.group(2));
                            this.newFieldCount++;
                        }
                    }
                }
            }
            // We have all the new fields.  Add them here.
            if (! newFields.isEmpty())
                record.putAll(newFields);
            long nowTime = System.currentTimeMillis();
            if (nowTime - lastMessage >= 5000) {
                log.info("{} records scanned.  {} fields added.  {} genomes and {} features were not found.",
                        recordCount, this.newFieldCount, gNotFoundCount, fNotFoundCount);
            }
        }
        log.info("{} total records scanned in {}.  {} fields added.  {} genomes and {} features were not found.",
                recordCount, this.jsonFile, this.newFieldCount, gNotFoundCount, fNotFoundCount);
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
     * @param newFields		new-fields map to receive the ID mapping
     * @param newId			ID to store
     * @param newName		base name for the new key
     * @param suffix		optional suffix for the new key
     */
    private void putNewId(Map<String, String> newFields, String newId, String newName, String suffix) {
        // Compute the full name for the new mapping.
        String fullName = (suffix != null ? newName + suffix : newName);
        newFields.put(fullName, newId);
    }

    /**
     * Save the JSON data back to the file.
     *
     * @throws IOException
     */
    public void save() throws IOException {
        if (this.newFieldCount == 0)
            log.info("No changes made to {}.", this.jsonFile);
        else {
            log.info("Saving {} with {} new fields.", this.jsonFile, this.newFieldCount);
            String jsonString = Jsoner.prettyPrint(Jsoner.serialize(this.jsonList));
            try (PrintWriter jsonWriter = new PrintWriter(this.jsonFile)) {
                jsonWriter.write(jsonString);
            }
        }
    }

	/**
	 * @return the fidMapper
	 */
	public FidMapper getFidMapper() {
		return fidMapper;
	}

}
