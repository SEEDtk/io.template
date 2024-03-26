/**
 *
 */
package org.theseed.io.template.utils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.FieldInputStream;
import org.theseed.io.LineReader;
import org.theseed.io.template.LineTemplate;
import org.theseed.io.template.output.ITemplateWriter;
import org.theseed.io.template.output.TemplateHashWriter;
import org.theseed.io.template.output.TemplatePrintWriter;

/**
 * This class performs template processing, and multiple instances allow us to process multiple templates in parallel.
 * See the LineTemplate class for more information about templates, and the TemplateTextProcessor class for more
 * about how this class is used.
 *
 * @author Bruce Parrello
 *
 */
public class TextFactory {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(TextFactory.class);
    /** compiled main line template */
    private LineTemplate template;
    /** list of linked-template descriptors */
    private List<LinkedTemplateDescriptor> linkedTemplates;
    /** current base input directory */
    private File currentDir;
    /** global template output */
    private TemplateHashWriter globals;

    /**
     * Construct a template text factory.
     *
     * @param currDir		directory of source files
     * @param globalHash	hash-writer for the globals (or NULL if none)
     *
     * @throws IOException
     */
    protected TextFactory(File currDir, TemplateHashWriter globalHash) {
        this.currentDir = currDir;
        this.globals = globalHash;
    }

    /**
     * Process a global template.  Note that all exceptions are converted to unchecked.
     *
     * @param currDir		directory of source files
     * @param source		source template file
     * @param target 		target output file
     *
     * @return the hash writer containing the global text
     *
     */
    public static TemplateHashWriter processGlobalTemplate(File currDir, File source) {
        // Construct a template factory without globals.
        TextFactory factory = new TextFactory(currDir, null);
        // Create the output writer.  We save it as the global in case there is a self-reference in
        // the template.
        factory.globals = new TemplateHashWriter();
        // Execute the template to compute the global text strings.
        try {
            factory.executeTemplates(source, factory.globals);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParseFailureException e) {
            throw new RuntimeException(e);
        }
        return factory.globals;
    }


    /**
     * Process a normal template.  Note that all exceptions are converted to unchecked.
     *
     * @param currDir		directory of source files
     * @param source		source template file
     * @param target 		target output file
     * @param globalHash	hash writer containing the global text
     *
     * @return the number of tokens generated
     *
     * @throws ParseFailureException
     * @throws IOException
     */
    public static long processNormalTemplate(File currDir, File source, File target, TemplateHashWriter globalHash) {
        long retVal = 0;
        // Construct a template factory with globals.
        TextFactory factory = new TextFactory(currDir, globalHash);
        // Save the globals.
        factory.globals = globalHash;
        // Execute the template to compute the global text strings.
        try {
            // Create the output writer.
            TemplatePrintWriter writer = new TemplatePrintWriter(target);
            factory.executeTemplates(source, writer);
            // Return the token count.
            retVal = writer.getTokenCount();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ParseFailureException e) {
            throw new RuntimeException(e);
        }
        return retVal;
    }

    /**
     * Process the specified template file into the specified output writer.
     *
     * @param source	source template file name
     * @param writer	template output writer
     *
     * @throws IOException
     * @throws ParseFailureException
     */
    protected void executeTemplates(File source, ITemplateWriter writer)
            throws IOException, ParseFailureException {
        // Initialize the link lists.
        this.linkedTemplates = new ArrayList<LinkedTemplateDescriptor>();
        // We start by reading a main template, then all its linked templates.  When we hit end-of-file or a
        // #main marker, we run the main template and output the results.
        try (LineReader templateStream = new LineReader(source)) {
            // We will buffer each template group in here.  A group starts with a #main header and runs through
            // the next #main or end-of-file.
            List<String> templateGroup = new ArrayList<String>(100);
            // Special handling is required for the first header.
            Iterator<String> streamIter = templateStream.iterator();
            if (! streamIter.hasNext())
                throw new IOException("No data found in template file.");
            // First we process the choice records.
            String savedHeader = streamIter.next();
            while (savedHeader.startsWith("#choices")) {
                this.processChoicesLine(savedHeader, writer);
                if (! streamIter.hasNext()) {
                    log.info("No template groups found in template file.");
                    savedHeader = "";
                } else
                    savedHeader = streamIter.next();
            }
            // Only proceed if there is template data other than the choices.
            if (! savedHeader.isBlank()) {
                if (! StringUtils.startsWith(savedHeader, "#main"))
                    throw new IOException("Template file does not start with #main header.");
                // Now we have the main header saved, and we can process each template group.
                // Loop through the template lines.
                for (var templateLine : templateStream) {
                    if (StringUtils.startsWith(templateLine, "#main")) {
                        // New group starting.  Process the old group.
                        this.processGroup(savedHeader, templateGroup, writer);
                        // Set up for the next group.
                        templateGroup.clear();
                        savedHeader = templateLine;
                    } else if (! templateLine.startsWith("##"))
                        templateGroup.add(templateLine);
                }
                // Process the residual group.
                this.processGroup(savedHeader, templateGroup, writer);
            }
        } finally {
            writer.close();
        }
    }

    /**
     * Process a #choices line and ask the writer to save the choice sets.
     *
     * @param savedHeader	input choices line to parse
     * @param writer		output template writer
     *
     * @throws ParseFailureException
     * @throws IOException
     */
    private void processChoicesLine(String savedHeader, ITemplateWriter writer) throws ParseFailureException, IOException {
        String[] parts = StringUtils.split(savedHeader);
        if (parts.length < 3)
            throw new ParseFailureException("Choices header requires at least a filename and a column name.");
        // Create a file name.
        File choiceFile = new File(this.currentDir, parts[1]);
        String[] cols = Arrays.copyOfRange(parts, 2, parts.length);
        writer.readChoiceLists(choiceFile, cols);
    }

    /**
     * Process a single template group.  The first template is the main one, and the
     * remaining templates are all linked.
     *
     * @param savedHeader		saved header line for the main template
     * @param templateGroup		list of non-comment lines making up the template group
     * @param writer			output print writer
     *
     * @throws IOException
     * @throws ParseFailureException
     */
    private void processGroup(String savedHeader, List<String> templateGroup, ITemplateWriter writer)
            throws IOException, ParseFailureException {
        if (templateGroup.isEmpty())
            log.warn("Empty template group skipped.");
        else {
            // We will buffer each template's data lines in here.
            List<String> templateLines = new ArrayList<String>(templateGroup.size());
            Iterator<String> groupIter = templateGroup.iterator();
            // Process the main template.
            String linkHeader = null;
            while (groupIter.hasNext() && linkHeader == null) {
                String templateLine = groupIter.next();
                if (templateLine.startsWith("#linked"))
                    linkHeader = templateLine;
                else
                    templateLines.add(templateLine);
            }
            if (templateLines.isEmpty())
                throw new IOException("Template group has no main template.");
            // Get the input file for the main template.
            String[] pieces = StringUtils.split(savedHeader);
            if (pieces.length < 2)
                throw new IOException("Main template has no input file name.");
            else if (pieces.length < 3)
                throw new IOException("Main template for " + pieces[1] + " has no key field name.");
            // Get the file name and the key field name.
            String baseFileName = pieces[1];
            File mainFile = new File(this.currentDir, baseFileName);
            String keyName = pieces[2];
            try (FieldInputStream mainStream = FieldInputStream.create(mainFile)) {
                int keyIdx = mainStream.findField(keyName);
                this.buildMainTemplate(mainStream, savedHeader, templateLines);
                // If there are any linked templates, we build them here.
                if (linkHeader != null) {
                    templateLines.clear();
                    while (groupIter.hasNext()) {
                        String templateLine = groupIter.next();
                        if (templateLine.startsWith("#choices"))
                            throw new ParseFailureException("Invalid placement of #choices record.");
                        else if (templateLine.startsWith("#linked")) {
                            // We have a new template, so build the one we've accumulated.
                            this.buildLinkedTemplate(mainStream, linkHeader, templateLines);
                            // Set up for the next template.
                            templateLines.clear();
                            linkHeader = templateLine;
                        } else
                            templateLines.add(templateLine);
                    }
                    // Build the residual template.
                       this.buildLinkedTemplate(mainStream, linkHeader, templateLines);
                    // Now link up the keys to the main file.
                    log.info("Finding keys for {} linked templates.", this.linkedTemplates.size());
                    for (var linkedTemplate : this.linkedTemplates)
                        linkedTemplate.findMainKey(mainStream);
                }
                // All the templates are compiled.  Now we run through the main file.
                // We want to count the number of lines read, the number of linked lines added,
                // and the total text length generated.
                int count = 0;
                int linked = 0;
                long length = 0;
                long lastMessage = System.currentTimeMillis();
                // This list is used to buffer the main template and the linked ones.
                List<String> translations = new ArrayList<String>(this.linkedTemplates.size() + 1);
                // Read the input file.
                log.info("Reading input file {}.", mainFile);
                for (var line : mainStream) {
                    count++;
                    String translation = this.template.apply(line);
                    // Only produce output if the result is nonblank.
                    if (! StringUtils.isBlank(translation)) {
                        translations.add(translation);
                        // Get the linked templates.
                        for (var linkedTemplate : this.linkedTemplates) {
                            List<String> found = linkedTemplate.getStrings(line);
                            linked += found.size();
                            translations.addAll(found);
                        }
                        // Join them all together.
                        translation = StringUtils.join(translations, ' ');
                        // Now print the result.
                        length += translation.length();
                        String keyValue = line.get(keyIdx);
                        writer.write(baseFileName, keyValue, translation);
                        translations.clear();
                    }
                    if (log.isInfoEnabled()) {
                        long now = System.currentTimeMillis();
                        if (now - lastMessage >= 5000)
                            log.info("{} lines read, {} characters written.", count, length);
                        lastMessage = now;
                    }
                }
                if (this.linkedTemplates.size() > 0)
                    log.info("{} linked lines were incorporated from {} templates.", linked, this.linkedTemplates.size());
                log.info("{} lines were translated to {} characters of output.", count, length);
            }
        }
    }

    /**
     * Build a main template.  The main template clears the linked-template queue and is stored in the
     * main data structures.
     *
     * @param mainStream		input file stream to which the template will be applied
     * @param savedHeader		header line for this linked template
     * @param templateLines		list of template text lines
     *
     * @throws ParseFailureException
     * @throws IOException
     */
    protected void buildMainTemplate(FieldInputStream mainStream, String savedHeader, List<String> templateLines)
            throws IOException, ParseFailureException {
        // Form the template into a string.
        String templateString = LinkedTemplateDescriptor.buildTemplate(templateLines);
        // Compile the string.
        log.info("Compiling main template from {} lines.", templateLines.size());
        this.template = new LineTemplate(mainStream, templateString, this.globals);
        // Denote there are no linked templates yet.
        this.linkedTemplates.clear();
    }

    /**
     * Build a linked template.  The saved header must be parsed to extract the two key field names.
     * Then the template descriptor is built from the key field names, the template lines, and the
     * linked file name.  Finally, the linked template is added to the linked-template queue.
     *
     * @param mainStream		input file stream for the main template
     * @param savedHeader		header line for this linked template
     * @param templateLines		list of template text lines
     *
     * @throws ParseFailureException
     * @throws IOException
     */
    protected void buildLinkedTemplate(FieldInputStream mainStream, String savedHeader, List<String> templateLines)
            throws ParseFailureException, IOException {
        // Insure we have data in the template.
        if (templateLines.isEmpty())
            throw new IOException("Empty linked template with header \"" + savedHeader + "\".");
        // Parse the header.
        String[] tokens = StringUtils.split(savedHeader);
        String mainKey;
        String linkKey;
        File linkFile;
        switch (tokens.length) {
        case 2 :
            throw new ParseFailureException("Template header \"" + savedHeader + "\" has two few parameters.");
        case 3 :
            // Single key name, so it is the same for both files.
            mainKey = tokens[1];
            linkKey = tokens[1];
            linkFile = new File(this.currentDir, tokens[2]);
            break;
        default :
            // Two key names, so we use both.
            mainKey = tokens[1];
            linkKey = tokens[2];
            linkFile = new File(this.currentDir, tokens[3]);
            break;
        }
        // Create the template and add it to the queue.
        var template = new LinkedTemplateDescriptor(mainKey, linkKey, templateLines, linkFile, this.globals);
        this.linkedTemplates.add(template);
    }

}
