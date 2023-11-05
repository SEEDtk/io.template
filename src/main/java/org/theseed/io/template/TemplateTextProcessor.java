/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.io.FieldInputStream;
import org.theseed.io.LineReader;
import org.theseed.io.template.LineTemplate;
import org.theseed.io.template.output.ITemplateWriter;
import org.theseed.io.template.output.TemplateHashWriter;
import org.theseed.io.template.output.TemplatePrintWriter;
import org.theseed.utils.BaseProcessor;
import org.theseed.utils.ParseFailureException;

/**
 * This sub-command converts incoming files to text paragraphs using a LineTemplate.
 *
 * The LineTemplate is specified using a plain text file.  We allow multiple templates, some of which are primary
 * and some of which can be linked.
 *
 * Each template can have multiple lines, but they are concatenated into a single line.  The template contains
 * literal text interspersed with variable references and commands.  The variables are column identifiers
 * for the relevant input file, while the commands perform useful tasks like creating text lists or performing
 * conditionals.  Leading and trailing whitespace on each line of the template file will be trimmed.  If the
 * first non-white character in a line is part of a literal, then a space is added in front when it is concatenated.
 *
 * The template file generally contains multiple templates for different files.  The source files are in a single
 * directory, and each template header contains the name of a file in that diriectory. A main template has a header
 * that says "#main" followed by a file name (the main file), and the name of a key field from the input file.
 * These templates generate output.  There are also linked templates. These begin with a header line that says
 * "#linked" and represent data lines that are joined with a main file using a column in that file and a column
 * in the linked file.  The column specifiers are specified as positional parameters on the header line,
 * space-delimited. So,
 *
 * 		#linked patric_id feature_id fileName
 *
 * would join based on the "patric_id" field in the main file using the "feature_id" field in the secondary file with
 * name "fileName" in the input directory.  If there are only two fields instead of three, then the two column names are
 * assumed to be the same.  For each matching line in the secondary file, the applied template text
 * will be added to the current-line output from the main file.  The linked files must follow the main file to which
 * they apply.
 *
 * For a global template, you can also have a #choices record.  The choices record has as its first parameter a file
 * name and the remaining parameters are the names of columns to read into choice sets.  All choices records must be
 * at the beginning of the file.
 *
 * The first record of the template file absolutely must be "#main".  This is how we know we have the right kind of
 * file.  Subsequent lines that begin with "##" are treated as comments.
 *
 * THe positional parameters are the name of the template file, the name of the input directory containing the files named
 * in the templates, and the name of the output directory.  If the "--recurse" option is specified, all subdirectories
 * if the named input directory will be processed rather than the directory itself.  Each output file will have a name
 * consisting of the input directory base name with a suffix of ".text".
 *
 * The input files are all field-input streams and the type is determined by the filename extension.  An extension of
 * ".tbl", ".tab", ".txt", or ".tsv" implies a tab-delimited file and an extension of ".json" a JSON list file.
 *
 * Linked templates are designed to handle one-to-many relationships; we also support many-to-one relationships
 * through the $include directive.  To use this directive, you must specify a global template directory.  The
 * template file for this directory will be named "global.tmpl", and instead of being output, the template strings
 * will be put into a database for callout by $include.
 *
 * The following command-line options are supported.
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -R	recursively process the input directories
 * -G	name of the global template directory (optional)
 * -o	name of the output file (mutually exclusive with -R)
 *
 * --missing	only files that don't already exist will be output
 * --clear		erase the output directory before processing
 *
 * @author Bruce Parrello
 *
 */
public class TemplateTextProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    protected static Logger log = LoggerFactory.getLogger(TemplateTextProcessor.class);
    /** compiled main line template */
    private LineTemplate template;
    /** list of linked-template descriptors */
    private List<LinkedTemplateDescriptor> linkedTemplates;
    /** list of linked-template input files */
    private List<File> linkedFiles;
    /** list of input directories to process */
    private List<File> inDirList;
    /** current base input directory */
    private File currentDir;
    /** global template file */
    private File globalTemplateFile;
    /** global template output */
    private TemplateHashWriter globals;

    // COMMAND-LINE OPTIONS

    /** if specified, the input directory will be processed recursively */
    @Option(name = "--recurse", aliases = { "--recursive", "-R" }, usage = "if specified, each input subdirectory will be processed rather than the input directory itself")
    private boolean recurseFlag;

    /** if specified, an an output file exists, the input directory will be skipped */
    @Option(name = "--missing", usage = "if specified, directories which already have output files will be skipped")
    private boolean missingFlag;

    /** if specified, the output directory will be cleared before processing */
    @Option(name = "--clear", usage = "if specified, the output directory will be cleared before processing")
    private boolean clearFlag;

    /** if specified, the name of the output file; single-file mode only */
    @Option(name = "--output", aliases = { "-o" }, metaVar = "output.text",
            usage = "for single output files, the desired output file base name")
    private String outFile;

    /** global template directory */
    @Option(name = "--global", aliases = { "-G" }, metaVar = "globalDir",
            usage = "if specified, a global input/template directory for $include directives")
    private File globalDir;

    /** name of the template text file */
    @Argument(index = 0, metaVar = "templateFile.txt", usage = "name of the file containing the text of the template", required = true)
    private File templateFile;

    /** name of the input directory */
    @Argument(index = 1, metaVar = "inputDir", usage = "input directory", required = true)
    private File inputDir;

    /** name of the input directory */
    @Argument(index = 2, metaVar = "outDir", usage = "output directory", required = true)
    private File outDir;

    @Override
    protected void setDefaults() {
        this.recurseFlag = false;
        this.missingFlag = false;
        this.clearFlag = false;
        this.globalDir = null;
        this.outFile = null;
    }

    @Override
    protected boolean validateParms() throws IOException, ParseFailureException {
        // Verify that the template file exists.
        if (! this.templateFile.canRead())
            throw new FileNotFoundException("Template file " + this.templateFile + " is not found or unreadable.");
        // Set up the input directories.
        if (! this.inputDir.isDirectory())
            throw new FileNotFoundException("Input directory " + this.inputDir + " is not found or invalid.");
        if (! this.recurseFlag) {
            log.info("Processing single input directory {}.", this.inputDir);
            this.inDirList = List.of(this.inputDir);
        } else {
            File[] subDirs = this.inputDir.listFiles(File::isDirectory);
            this.inDirList = Arrays.asList(subDirs);
            log.info("{} subdirectories of {} will be processed.", subDirs.length, this.inputDir);
        }
        // Validate the global directory.
        if (this.globalDir != null) {
            if (! this.globalDir.isDirectory())
                throw new FileNotFoundException("Global template directory " + this.globalDir
                        + " is not found or invalid.");
            else {
                this.globalTemplateFile = new File(this.globalDir, "global.tmpl");
                if (! this.globalTemplateFile.canRead())
                    throw new FileNotFoundException("Global template file " + this.globalTemplateFile
                            + " is not found or unreadable.");
            }
        }
        // Insure the output file is not being misused.
        if (this.outFile != null && this.recurseFlag)
            throw new ParseFailureException("Cannot specify an output file name in recursive mode.");
        // Validate the output directory.
        if (! this.outDir.isDirectory()) {
            log.info("Creating output directory {}.", this.outDir);
            FileUtils.forceMkdir(this.outDir);
        } else if (this.clearFlag) {
            log.info("Erasing output directory {}.", this.outDir);
            FileUtils.cleanDirectory(this.outDir);
        } else
            log.info("Output will be to directory {}.", this.outDir);
        // Initialize the linking structures.
        this.linkedTemplates = new ArrayList<LinkedTemplateDescriptor>();
        this.linkedFiles = new ArrayList<File>();
        this.template = null;
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Process the global directory.
        this.globals = new TemplateHashWriter();
        if (this.globalDir != null) {
            log.info("Computing global data from {}.", this.globalDir);
            this.currentDir = this.globalDir;
            this.executeTemplates(this.globalTemplateFile, globals);
        }
        // Loop through the input directories.
        int dirCount = 0;
        for (File baseDir : this.inDirList) {
            this.currentDir = baseDir;
            // Compute the output file for this directory.
            ITemplateWriter writer = null;
            String name = baseDir.getName();
            boolean skipFile = false;
            File currOutFile;
            if (this.outFile == null)
                currOutFile = new File(this.outDir, name + ".text");
            else
                currOutFile = new File(this.outDir, this.outFile);
            dirCount++;
            if (this.missingFlag && currOutFile.exists()) {
                log.info("Skipping input directory #{} {}-- output file {} exists.", dirCount, baseDir, currOutFile);
                skipFile = true;
            } else {
                writer = new TemplatePrintWriter(currOutFile);
                log.info("Processing data from directory #{} {} into text file {}.", dirCount, baseDir, currOutFile);
            }
            if (! skipFile)
                this.executeTemplates(this.templateFile, writer);
        }
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
    private void executeTemplates(File source, ITemplateWriter writer)
            throws IOException, ParseFailureException {
        // Clear the link lists.
        this.linkedTemplates.clear();
        this.linkedFiles.clear();
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
                log.info("Reading input file.");
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
                        if (log.isInfoEnabled()) {
                            long now = System.currentTimeMillis();
                            if (now - lastMessage >= 5000)
                                log.info("{} lines read, {} characters written.", count, length);
                            lastMessage = now;
                        }
                        translations.clear();
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
    private void buildMainTemplate(FieldInputStream mainStream, String savedHeader, List<String> templateLines)
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
    private void buildLinkedTemplate(FieldInputStream mainStream, String savedHeader, List<String> templateLines)
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
