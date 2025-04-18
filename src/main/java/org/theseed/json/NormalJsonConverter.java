/**
 *
 */
package org.theseed.json;

import java.io.File;
import java.io.IOException;

import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonException;
import com.github.cliftonlabs.json_simple.JsonObject;

/**
 * This object does the conversion on a json file that requires no special processing.
 *
 * @author Bruce Parrello
 *
 */
public class NormalJsonConverter extends JsonConverter {

    /**
     * Construct a JSON converter for a normal JSON dump file.
     *
     * @param fidMap		feature ID mapper
     * @param dir			current file directory
     * @param fileName		name of the file in that directory
     * @param mode			key-conversion mode
     *
     * @throws IOException
     * @throws JsonException
     */
    public NormalJsonConverter(FidMapper fidMap, File dir, String fileName, JsonConverter.KeyMode mode)
    		throws IOException, JsonException {
        super(fidMap, dir, fileName, mode);
    }

    @Override
    protected void preProcessRecord(JsonObject record) {
        // No pre-processing is required.
    }

}
