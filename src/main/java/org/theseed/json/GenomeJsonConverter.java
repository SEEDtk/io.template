/**
 *
 */
package org.theseed.json;

import java.io.File;
import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonKey;
import com.github.cliftonlabs.json_simple.JsonObject;

/**
 * This object converts a genome.json file.  When this file is processed, we must set up for a new genome.
 *
 * @author Bruce Parrello
 *
 */
public class GenomeJsonConverter extends JsonConverter {

    /**
     * This enum defines the keys used and their default values.
     */
    public static enum SpecialKeys implements JsonKey {
        GENOME_ID(null),
        GENOME_NAME(null);

        private final Object m_value;

        SpecialKeys(final Object value) {
            this.m_value = value;
        }

        /**
         * This is the string used as a key in the incoming JsonObject map.
         */
        @Override
        public String getKey() {
            return this.name().toLowerCase();
        }

        /**
         * This is the default value used when the key is not found.
         */
        @Override
        public Object getValue() {
            return this.m_value;
        }

    }

    /**
     * Construct a new genome.json converter.
     *
     * @param fidMap		feature ID mapper used to save the new IDs
     * @param dir			input directory
     * @param mode			key-conversion mode
     *
     * @throws IOException
     * @throws JsonException
     */
    public GenomeJsonConverter(FidMapper fidMap, File dir, JsonConverter.KeyMode mode) throws IOException, JsonException {
        super(fidMap, dir, "genome.json", mode);
    }

    @Override
    protected void preProcessRecord(JsonObject record) {
        // Get the genome ID and name and perform the setup.
        String genomeId = record.getStringOrDefault(SpecialKeys.GENOME_ID);
        String genomeName = record.getStringOrDefault(SpecialKeys.GENOME_NAME);
        if (StringUtils.isBlank(genomeId) || StringUtils.isBlank(genomeName))
            log.error("Malformed genome record found in {}.", this.getFile());
        else {
            this.getFidMapper().setup(genomeId, genomeName);
        }
    }

}
