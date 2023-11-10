/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.FieldInputStream;
import org.theseed.io.template.output.TemplateHashWriter;

/**
 * This object is used to manage a linked template.  It contains the index of the key column in the main
 * file, and a map of key values to template results.  It also provides a utility method to process a
 * list of template file lines into a template string.
 *
 * We construct the linked template with a list of template file lines and the name of a key column.
 * The linked file is then passed in to create the main hash.  The hash may map a single key value
 * to multiple output lines, but these are concatenated.
 *
 * @author Bruce Parrello
 *
 */
public class LinkedTemplateDescriptor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(LinkedTemplateDescriptor.class);
    /** name of main file key field */
    private String mainFileKey;
    /** map of key values to output lines */
    private Map<String, List<String>> outputMap;
    /** index of the main file key field */
    private int mainKeyIdx;
    /** list to return if nothing is found */
    private static final List<String> NO_STRINGS = Collections.emptyList();

    /**
     * Construct a linked-template descriptor.
     *
     * @param mainKey			index (1-based) or name of main-file column containing the join key
     * @param linkKey			index (1-based) or name of linked-file column containing the join key
     * @param templateLines		list of template file lines
     * @param linkedFile		name of linked file
     * @param globals 			global-data structure
     *
     * @throws IOException
     * @throws ParseFailureException
     */
    public LinkedTemplateDescriptor(String mainKey, String linkKey, List<String> templateLines, File linkedFile,
            TemplateHashWriter globals) throws IOException, ParseFailureException {
        log.info("Building template based on join from {} to {}.", mainKey, linkKey);
        this.mainFileKey = mainKey;
        String templateString = buildTemplate(templateLines);
        // Default the main key index to the first column.
        this.mainKeyIdx = 0;
        // Create the output map.
        this.outputMap = new HashMap<String, List<String>>(100);
        // Open the linked file and build the template lines.
        try (FieldInputStream linkStream = FieldInputStream.create(linkedFile)) {
            log.info("Reading data lines from linked file {}.", linkedFile);
            // Count the output lines.
            int outCount = 0;
            // Find the key column.
            int keyIdx = linkStream.findField(linkKey);
            // Construct the template.
            LineTemplate template = new LineTemplate(linkStream, templateString, globals);
            // Loop through the link file.
            for (var line : linkStream) {
                String key = line.get(keyIdx);
                // Only proceed if the key is nonblank.
                if (! StringUtils.isBlank(key)) {
                    String translation = template.apply(line);
                    List<String> keyList = this.outputMap.computeIfAbsent(key, x -> new ArrayList<String>());
                    keyList.add(translation);
                    outCount++;
                }
            }
            log.info("{} keys linked to {} output lines.", this.outputMap.size(), outCount);
        }
    }

    /**
     * Convert a list of template-file lines into a template string.  The lines are trimmed, and if a trimmed
     * line does not begin with a command, it is joined with a space; otherwise, the lines are concatenated.
     *
     * @param template	list of template file lines
     *
     * @return a single template string
     */
    public static String buildTemplate(List<String> template) {
        int count = 0;
        StringBuffer templateBuffer = new StringBuffer(template.size() * 80);
        for (String templateLine : template) {
            String newLine = StringUtils.strip(templateLine);
            if (templateBuffer.length() > 0 && ! newLine.startsWith("{{"))
                templateBuffer.append(' ');
            templateBuffer.append(newLine);
            count++;
        }
        String retVal = templateBuffer.toString();
        log.info("{}-character template string built from {} template lines.", retVal.length(), count);
        return retVal;
    }

    /**
     * Compute the index of the main-file key and save it for future use.
     *
     * @param inputStream	input stream for the main file
     *
     * @throws IOException
     */
    public void findMainKey(FieldInputStream inputStream) throws IOException {
        this.mainKeyIdx = inputStream.findField(mainFileKey);

    }

    /**
     * This method uses the main map to return all the translated strings for the specified line.
     *
     * @param line	main file input line
     *
     * @return a list of translated strings from the linked file, or an empty list if none
     */
    public List<String> getStrings(FieldInputStream.Record line) {
        String mainKey = line.get(mainKeyIdx);
        List<String> retVal;
        if (StringUtils.isBlank(mainKey))
            retVal = NO_STRINGS;
        else {
            retVal = this.outputMap.get(mainKey);
            if (retVal == null)
                retVal = NO_STRINGS;
        }
        return retVal;
    }

}
