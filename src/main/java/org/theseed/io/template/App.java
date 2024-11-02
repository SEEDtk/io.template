/**
 *
 */
package org.theseed.io.template;

import java.util.Arrays;

import org.theseed.basic.BaseProcessor;

/**
 * Commands for generating texts from templates and field input streams.
 *
 * text			generate a text template from one or more directories of data
 * jsonMagic	add magic IDs to genome and subsystem dumps
 * pubmed		find all the pubmed IDs in JSON dump directories
 *
 */
public class App {

    /** static array containing command names and comments */
    protected static final String[] COMMANDS = new String[] {
             "text", "generate a text template from one or more directories of data",
             "jsonMagic", "add magic IDs to genome and subsystem dumps",
             "pubmed", "find all the pubmed IDs in JSON dump directories",
    };

    public static void main( String[] args ) {
        // Get the control parameter.
        String command = args[0];
        String[] newArgs = Arrays.copyOfRange(args, 1, args.length);
        BaseProcessor processor;
        // Determine the command to process.
        switch (command) {
        case "text" :
            processor = new TemplateTextProcessor();
            break;
        case "jsonMagic" :
            processor = new MagicJsonProcessor();
            break;
        case "pubmed" :
            processor = new PubmedProcessor();
            break;
        case "-h" :
        case "--help" :
            processor = null;
            break;
        default :
            throw new RuntimeException("Invalid command " + command + ".");
        }
        if (processor == null)
            BaseProcessor.showCommands(COMMANDS);
        else {
            boolean ok = processor.parseCommand(newArgs);
            if (ok) {
                processor.run();
            }
        }
    }
}
