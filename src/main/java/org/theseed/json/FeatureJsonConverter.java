/**
 *
 */
package org.theseed.json;

import java.io.File;
import java.io.IOException;

import org.theseed.basic.ParseFailureException;
import org.theseed.magic.FidMapper;

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
        FEATURE_ID(null),
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
     */
    public FeatureJsonConverter(FidMapper fidMap, File dir) throws IOException {
        super(fidMap, dir, "genome_feature.json");
    }

    @Override
    protected void preProcessRecord(JsonObject record) {
        // Here we need to compute the feature ID word so it is available later.
        // This only happens if there is a patric ID.
        String fid = record.getStringOrDefault(SpecialKeys.PATRIC_ID);
        if (fid != null) {
            String featureId = record.getStringOrDefault(SpecialKeys.FEATURE_ID);
            String function = record.getStringOrDefault(SpecialKeys.PRODUCT);
            // Note the function is only used for coding regions, and the feature ID is optional.
            try {
                // Store the FIG id mapping and save the feature ID word.
                String fidWord = this.getFidMapper().getMagicFid(fid, function);
                // If there is a feature ID, save a mapping for it, too.
                if (featureId != null) {
                    this.getFidMapper().saveFidAlias(featureId, fidWord);
                }
            } catch (ParseFailureException e) {
                // Here we have a feature ID for the wrong genome.  Log the error
                // and skip the record.
                log.error(e.toString());
            }
        }
        // TODO code for preProcessRecord

    }
    // FIELDS
    // TODO data members for FeatureJsonConverter

    // TODO constructors and methods for FeatureJsonConverter
}
