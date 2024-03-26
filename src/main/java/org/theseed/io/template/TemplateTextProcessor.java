/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.template.output.TemplateHashWriter;
import org.theseed.io.template.utils.TextFactory;


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
    /** map of input directories to output files for processing */
    private Map<File, File> inDirMap;
    /** global template file */
    private File globalTemplateFile;
    /** global template output */
    private TemplateHashWriter globals;
    /** output token counter */
    private long tokenCount;

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
            // Here we have a single input directory and there will be a single output directory.
            String outName;
            if (this.outFile != null)
                outName = this.outFile;
            else
                outName = this.inputDir.getName();
            this.inDirMap = Map.of(this.inputDir, new File(this.outDir, outName));
            log.info("Processing single input directory {} to output file {}..", this.inputDir, this.inDirMap.get(this.inputDir));
        } else if (this.outFile != null)
            throw new ParseFailureException("Cannot specify output file name for multi-directory input.");
        else {
            // Here we have multiple input directories and multiple output files.
            File[] subDirs = this.inputDir.listFiles(File::isDirectory);
            this.inDirMap = new HashMap<File, File>(subDirs.length * 4 / 3 + 1);
            for (File subDir : subDirs) {
                File outDirFile = new File(this.outDir, subDir.getName() + ".text");
                if (! this.missingFlag || ! outDirFile.exists())
                    this.inDirMap.put(subDir, outDirFile);
            }
            log.info("{} subdirectories of {} will be processed.", this.inDirMap.size(), this.inputDir);
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
        this.tokenCount = 0;
        return true;
    }

    @Override
    protected void runCommand() throws Exception {
        // Process the global directory.
        this.globals = new TemplateHashWriter();
        if (this.globalDir != null) {
            log.info("Computing global data from {}.", this.globalDir);
            this.globals = TextFactory.processGlobalTemplate(this.globalDir, this.globalTemplateFile);
        }
        // Process all the input directories.
        this.inDirMap.entrySet().parallelStream().forEach(x -> this.processTemplate(x));
        log.info("All templates processed.  {} tokens generated.", this.tokenCount);
    }

    /**
     * Convert a source directory to template text output.
     *
     * @param filePair	map entry containing the source and target file names.
     */
    private void processTemplate(Entry<File, File> filePair) {
        File source = filePair.getKey();
        File target = filePair.getValue();
        log.info("Procesing templates against {} to produce {}.", source, target);
        long tokens = TextFactory.processNormalTemplate(source, this.templateFile, target, this.globals);
        synchronized(this) {
            this.tokenCount += tokens;
            log.info("{} tokens produced so far.", this.tokenCount);
        }
    }


}
