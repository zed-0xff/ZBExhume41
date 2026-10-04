package me.zed_0xff.zb_exhume_41;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;

/**
 * CLI entry point for RecipeConverter.
 * Usage: RecipeConverterMain [file...]
 *   With file arguments: converts each file and prints to stdout.
 *   With no arguments: reads stdin, writes stdout.
 */
public class RecipeConverterMain {
    public static void main(String[] args) throws IOException {
        boolean quiet = false;

        ArrayList<String> files = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("--help") || arg.equals("-h")) {
                System.out.println("Usage: RecipeConverterMain [options] [file...]\n" +
                        "Options:\n" +
                        "  -h, --help    Show this help message and exit\n" +
                        "  -q, --quiet   Suppress informational messages");
                return;
            } else if (arg.equals("--quiet") || arg.equals("-q")) {
                quiet = true;
            } else {
                files.add(arg);
            }
        }

        RecipeConverter converter = new RecipeConverter();

        if (files.size() == 0) {
            if (!quiet) System.err.println("Reading from stdin. Press Ctrl+D (Unix) or Ctrl+Z (Windows) to end input.");
            System.out.print(converter.convertFile(new String(System.in.readAllBytes())));
        } else {
            if (!quiet) System.err.println("Converting files: " + String.join(", ", files));
            for (String path : files) {
                System.out.print(converter.convertFile(Files.readString(Paths.get(path))));
            }
        }
    }
}
