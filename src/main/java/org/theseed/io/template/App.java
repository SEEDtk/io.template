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
 * magicJson	added magic IDs to genome and subsystem dumps
 *
 */
public class App {

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
        case "magicJson" :
            processor = new MagicJsonProcessor();
            break;
        default:
            throw new RuntimeException("Invalid command " + command);
        }
        // Process it.
        boolean ok = processor.parseCommand(newArgs);
        if (ok) {
            processor.run();
        }
    }
}
