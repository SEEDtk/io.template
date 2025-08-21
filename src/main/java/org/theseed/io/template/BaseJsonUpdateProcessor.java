/**
 *
 */
package org.theseed.io.template;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.theseed.basic.BaseProcessor;
import org.theseed.basic.ParseFailureException;
import org.theseed.io.MasterGenomeDir;
import org.theseed.json.FeatureJsonConverter;
import org.theseed.json.JsonConverter;
import org.theseed.json.JsonConverter.KeyMode;
import org.theseed.json.NormalJsonConverter;
import org.theseed.magic.FidMapper;

import com.github.cliftonlabs.json_simple.JsonException;


/**
 * This is a base class that processes JSON dumps and handles genome and feature ID updates.
 *
 * The first directory specified must contain the genome dumps.  The "genome.json" file will contain the genome ID and
 * name, and will be used to set the current-genome information.  Then the feature definitions will be pulled from
 * "genome_feature.json".  After that, the other files will be scanned for feature and genome IDs, performing updates.
 *
 * The positional parameters are the name of the primary genome dump master directory, and then the names of the other dump
 * directories. Each dump directory contains subdirectories with the json files.  All the json files are processed and then
 * copied into new directories.
 *
 * The command-line options are as follows:
 *
 * -h	display command-line usage
 * -v	display more frequent log messages
 * -D	output directory (default is "Updated" in the current directory)
 *
 * --clear	if specified, the output directory will be erased before processing
 *
 * @author Bruce Parrello
 *
 */
public abstract class BaseJsonUpdateProcessor extends BaseProcessor {

    // FIELDS
    /** logging facility */
    private static final Logger log = LoggerFactory.getLogger(MagicJsonProcessor.class);
    /** feature ID mapper */
    private FidMapper fidMapper;
    /** list of genome directories to process */
    private MasterGenomeDir genomeDirs;
    /** key-processing mode */
    private JsonConverter.KeyMode keyMode;
    /** this set contains the special file names for the genome processor */
    protected static final Set<String> SPECIAL_FILE_SET = Set.of("genome.json", "genome_feature.json");
    /** general subdirectory filter */
    protected static final FileFilter SUB_DIR_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            return pathname.isDirectory();
        }
    };
    /** JSON file filter */
    protected static final FileFilter JSON_FILE_FILTER = new FileFilter() {
        @Override
        public boolean accept(File pathname) {
            boolean retVal = pathname.canRead();
            if (retVal)
                retVal = pathname.getName().endsWith(".json");
            return retVal;
        }
    };


    // COMMAND-LINE OPTIONS

    /** output directory */
    @Option(name = "--outDir", aliases = { "-D" }, usage = "output directory")
    private File outDir;

    /** if specified, the output directory will be erased before processing */
    @Option(name = "--clear", usage = "if specified, the output directory will be erased before processing")
    private boolean clearFlag;

    /** master genome dump directory */
    @Argument(index = 0, metaVar = "genomesDir", usage = "master genome JSON dump directory", required = true)
    private File genomesDir;

    /** additional directories */
    @Argument (index = 1, metaVar = "otherDir1 otherDir2 ...", usage = "other JSON dump directories to process")
    private List<File> otherDirs;

    @Override
    final protected void setDefaults() {
        this.otherDirs = new ArrayList<File>();
        this.keyMode = this.getKeyMode();
        this.outDir = new File(System.getProperty("user.dir"), "Updates");
        this.clearFlag = false;
        this.setJsonUpdateDefaults();
    }

    /**
	 * @return the key mode to use for this update
	 */
	protected abstract KeyMode getKeyMode();

	/**
	 * Set the subclass-specific defaults.
	 */
	protected abstract void setJsonUpdateDefaults();

	@Override
    final protected void validateParms() throws IOException, ParseFailureException {
        // Verify the genome directory.
        if (! this.genomesDir.isDirectory())
            throw new FileNotFoundException("Input genome master directory " + this.genomesDir + " is not found or invalid.");
        // Find all the genomes.
        this.genomeDirs = new MasterGenomeDir(this.genomesDir);
        if (this.genomeDirs.size() == 0)
            throw new FileNotFoundException("No usable genome subdirectories found in " + this.genomesDir + ".");
        log.info("{} genome subdirectories found in {}.", this.genomeDirs.size(), this.genomesDir);
        // Now verify the additional directories.
        for (File otherDir : otherDirs) {
            if (! otherDir.isDirectory())
                throw new FileNotFoundException("Additional master directory " + otherDir + " is not found or invalid.");
        }
        log.info("{} additional directories scheduled.", otherDirs.size());
        // Verify the output directory.
        if (! this.outDir.isDirectory()) {
        	log.info("Creating output directory {}.", this.outDir);
        	FileUtils.forceMkdir(this.outDir);
        } else if (this.clearFlag) {
        	log.info("Erasing output directory {}.", this.outDir);
        	FileUtils.cleanDirectory(this.outDir);
        } else
        	log.info("Output will be to directory {}.", this.outDir);
        // Process the subclass parameters.
        this.validateJsonUpdateParms();
        // Get the ID mapper.
        this.fidMapper = this.getFidMapper();
    }

	/**
	 * Validate the subclass-specific parameters. This happens BEFORE the id-mapper is initialized.
	 *
	 * @throws IOException
	 * @throws ParseFailureException
	 */
	protected abstract void validateJsonUpdateParms() throws IOException, ParseFailureException;

	/**
	 * @return the id-mapper for this update
	 */
	protected abstract FidMapper getFidMapper();

    @Override
    protected void runCommand() throws Exception {
        // We process the genome master directory first.
        log.info("Processing {} genome subdirectories.", this.genomeDirs.size());
        int gCount = 0;
        for (File genomeDir : this.genomeDirs) {
            gCount++;
            log.info("Processing genome {} of {} in directory {}.", gCount, this.genomeDirs.size(), genomeDir);
            // Get the list of json files.
            File[] jsonFiles = this.getJsonFiles(genomeDir);
            log.info("{} files to convert in {}.", jsonFiles.length, genomeDir);
            // Process the genome file first.
            this.convertGenome(this.fidMapper, genomeDir, this.genomesDir);
            // Now process the feature file.
            FeatureJsonConverter fConverter = new FeatureJsonConverter(this.fidMapper, genomeDir, this.keyMode);
            File outputFeatureFile = this.getOutputFile(fConverter.getFile());
            this.processConverter(fConverter, outputFeatureFile);
            // Finally, process the other files.
            for (File jsonFile : jsonFiles) {
                String fileName = jsonFile.getName();
                if (! SPECIAL_FILE_SET.contains(fileName)) {
                    NormalJsonConverter converter = new NormalJsonConverter(this.fidMapper, genomeDir, fileName, this.keyMode);
                    File outputJsonFile = this.getOutputFile(jsonFile);
                    this.processConverter(converter, outputJsonFile);
                }
            }
        }
        // Now process the other directory groups.
        int otherDirCount = 0;
        for (File otherDir : this.otherDirs) {
            File[] subDirs = otherDir.listFiles(SUB_DIR_FILTER);
            log.info("{} subdirectories to process in {}.", subDirs.length, otherDir);
            for (File subDir : subDirs) {
                File[] jsonFiles = this.getJsonFiles(subDir);
                if (jsonFiles.length == 0)
                    log.warn("No JSON files found in {}.", subDir);
                else {
                    log.info("{} files to convert found in {}.", jsonFiles.length, subDir);
                    for (File jsonFile : jsonFiles) {
                        String fileName = jsonFile.getName();
                        NormalJsonConverter converter = new NormalJsonConverter(this.fidMapper, subDir, fileName, JsonConverter.KeyMode.USE_WORD_KEY);
                        File outputJsonFile = this.getOutputFile(jsonFile);
                        this.processConverter(converter, outputJsonFile);
                        otherDirCount++;
                    }
                }
            }
        }
        log.info("{} total additional directories processed.", otherDirCount);
    }


    /**
     * @return the output file to use for this input file
     *
	 * @param jsonFile	JSON input file of interest
	 */
	protected abstract File getOutputFile(File jsonFile);

	/**
     * Convert the genome file for this genome.
     *
	 * @param mapper		id mapper
	 * @param genomeDir		current genome directory
	 * @param masterDir		master directory of genomes
	 *
     * @throws IOException
     * @throws JsonException
	 */
	protected abstract void convertGenome(FidMapper mapper, File genomeDir, File masterDir)
			throws IOException, JsonException;

	/**
     * @return an array of the JSON files in a JSON dump directory
     *
     * @param dumpDir	dump directory to scan
     */
    private File[] getJsonFiles(File dumpDir) {
        return dumpDir.listFiles(JSON_FILE_FILTER);
    }

    /**
     * Compute the output file corresponding to an input file. The output file is in the master output
     * directory, uses the last two levels of the input file path, and then contains the input file name.
     *
     * @param inFile	input JSON file
     *
     * @return the output file for a specified input file
     */
    protected File computeOutputFile(File inFile) {
    	String fileName = inFile.getName();
    	File parent = inFile.getParentFile();
    	String clusterName = parent.getName();
    	File grandParent = parent.getParentFile();
    	String masterName = grandParent.getName();
    	grandParent = new File(this.outDir, masterName);
    	parent = new File(grandParent, clusterName);
    	File retVal = new File(parent, fileName);
		// Verify that the parent directory exists.
		try {
			FileUtils.forceMkdirParent(retVal);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
    	return retVal;
    }

    /**
     * Process a JSON converter to produce a converted file.
     *
     * @param converter		converter to process
     * @param targetFile	directory to contain the output file
     *
     * @throws IOException
     * @throws JsonException
     */
    protected abstract void processConverter(JsonConverter converter, File targetFile) throws IOException, JsonException;

    /**
     * @return the name of the master output directory
     */
    public File getOutputDir() {
    	return this.outDir;
    }

}
