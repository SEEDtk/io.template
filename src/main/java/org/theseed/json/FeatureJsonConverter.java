/**
 *
 */
package org.theseed.json;

import java.io.File;
import java.io.IOException;

import org.apache.commons.lang3.StringUtils;
import org.theseed.basic.ParseFailureException;
import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonKey;
import com.github.cliftonlabs.json_simple.JsonObject;

/**
 * Here we have a genome_feature.json file to convert.  We need to generate a magic word ID for each feature.
 *
 * @author Bruce Parrello
 *
 */
public class FeatureJsonConverter extends JsonConverter {

    /**
     * This enum defines the keys used and their default values.
     */
    public static enum SpecialKeys implements JsonKey {
        PATRIC_ID(null),
        PRODUCT("hypothetical protein");

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
     * Construct a JSON converter for a genome feature file.
     *
     * @param fidMap	feature ID word mapper
     * @param dir		current genome directory
     *
     * @throws IOException
     * @throws JsonException
     */
    public FeatureJsonConverter(FidMapper fidMap, File dir) throws IOException, JsonException {
        super(fidMap, dir, "genome_feature.json");
    }

    @Override
    protected void preProcessRecord(JsonObject record) {
        // Here we need to compute the feature ID word so it is available later.
        // This only happens if there is a patric ID.
        String fid = record.getStringOrDefault(SpecialKeys.PATRIC_ID);
        if (! StringUtils.isBlank(fid)) {
            String function = record.getStringOrDefault(SpecialKeys.PRODUCT);
            // Note the function is only used for coding regions.
            try {
                // Store the FIG id mapping.
               this.getFidMapper().getNewFid(fid, function);
            } catch (ParseFailureException e) {
                // Here we have a feature ID for the wrong genome.  Log the error
                // and skip the record.
                log.error(e.toString());
            }
        }

    }

}
