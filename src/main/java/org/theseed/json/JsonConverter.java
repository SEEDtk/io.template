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
 * This method processes a JSON file, adding magic word IDs for the features and genomes.  The base
 * class provides a hook for preprocessing.  In this hook, the subclasses can handle setting up the
 * current genome, initializing patric IDs, and computing feature ID mappings.  The remainder will
 * update the file's JSON array in place and then store it back.
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
    protected static Logger log = LoggerFactory.getLogger(JsonConverter.class);
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
    /** match pattern for genome ID keys */
    public static final Pattern GENOME_KEY_PATTERN = Pattern.compile("genome_id(_[a-zA-Z0-9])?");
    /** match pattern for feature ID keys */
    public static final Pattern FEATURE_KEY_PATTERN = Pattern.compile("(patric_id|interactor)(_[a-zA-Z0-9])?");
    /** match pattern for phrase keys */
    public static final Pattern PHRASE_KEY_PATTERN = Pattern.compile("(gene_rule)");
    /** match pattern for feature IDs themselves */
    private static final Pattern FEATURE_ID_PATTERN = Pattern.compile("\\bfig\\|\\d+\\.\\d+\\.\\w+\\.\\d+\\b");

    /**
     * Construct a JSON converter for a specified file.
     *
     * @param fidMapper		feature ID mapper to use
     * @param dir			directory containing the file
     * @param fileName		base name of the file
     *
     * @throws IOException
     * @throws JsonException
     */
    public JsonConverter(FidMapper fidMap, File dir, String fileName) throws IOException, JsonException {
        // Save the feature ID mapper.
        this.fidMapper = fidMap;
        // Compute the file name.
        this.jsonFile = new File(dir, fileName);
        // Load the file into memory.
        log.info("Reading file {}.", fileName);
        try (FileReader reader = new FileReader(this.jsonFile)) {
            this.jsonList = (JsonArray) Jsoner.deserialize(reader);
        }
        log.info("Processing {} file {} with {} records.", dir, fileName, this.jsonList.size());
        // Denote there have been no modifications.
        this.newFieldCount = 0;
        this.changeFieldCount = 0;
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
                            String newId = this.getFidMapper().getMagicGenomeId(oldId);
                            if (newId == null)
                                ok = false;
                            else
                                newValues.add(newId);
                        }
                        if (ok) {
                            this.putNewId(newFields, newValues, "genome_word", m.group(1));
                        }
                    } else {
                        String oldId = value.toString();
                        String newId = this.getFidMapper().getMagicGenomeId(oldId);
                        if (newId == null) {
                            // Here we don't have an ID for this genome.  Count it as an error.
                            gNotFoundCount++;
                        } else {
                            this.putNewId(newFields, newId, "genome_word", m.group(1));
                            this.newFieldCount++;
                        }
                    }
                } else {
                    // Not a genome field.  Check for a feature field.
                    m = FEATURE_KEY_PATTERN.matcher(key);
                    if (m.matches()) {
                        String oldId = fieldEntry.getValue().toString();
                        String newId = this.getFidMapper().getMagicFid(oldId);
                        if (newId == null)
                            fNotFoundCount++;
                        else {
                            this.putNewId(newFields, newId, "feature_word", m.group(2));
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
                                String newFid = this.fidMapper.getMagicFid(fid);
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
     * @param newFields		new-fields map to receive the ID mapping
     * @param newId			ID to store
     * @param newName		base name for the new key
     * @param suffix		optional suffix for the new key
     */
    private void putNewId(Map<String, Object> newFields, Object newId, String newName, String suffix) {
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
        if (this.unchanged())
            log.info("No changes made to {}.", this.jsonFile);
        else {
            log.info("Saving {} with {} new fields.", this.jsonFile, this.newFieldCount);
            long lastMsg = System.currentTimeMillis();
            // Because of the large file sizes, we have to save the JSON one record at a time.
            try (PrintWriter jsonWriter = new PrintWriter(this.jsonFile)) {
                jsonWriter.println("[");
                int rCount = 0;
                for (var obj : this.jsonList) {
                    rCount++;
                    String objString = Jsoner.prettyPrint(Jsoner.serialize(obj));
                    if (rCount > 1)
                        jsonWriter.println(",");
                    jsonWriter.print(objString);
                    long nowTime = System.currentTimeMillis();
                    if (nowTime - lastMsg >= 5000) {
                        log.info("{} records written to {}.", rCount, this.jsonFile);
                        lastMsg = nowTime;
                    }
                }
                jsonWriter.println("\n]");
                log.info("{} total records written to {}.", rCount, this.jsonFile);
            }
        }
    }

    /**
     * @return TRUE if this file has not been changed
     */
    private boolean unchanged() {
        return (this.newFieldCount == 0 && this.changeFieldCount == 0);
    }

    /**
     * @return the fidMapper
     */
    public FidMapper getFidMapper() {
        return fidMapper;
    }

}
