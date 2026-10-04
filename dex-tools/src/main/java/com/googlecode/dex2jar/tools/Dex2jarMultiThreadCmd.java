package com.googlecode.dex2jar.tools;

import com.googlecode.d2j.dex.Dex2jar;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@BaseCmd.Syntax(cmd = "d2j-mt-dex2jar", syntax = "[options] <file0> [file1 ... fileN]", desc = "convert dex to jar")
public class Dex2jarMultiThreadCmd extends BaseCmd {

    public static void main(String... args) {
        new Dex2jarMultiThreadCmd().doMain(args);
    }

    @Opt(opt = "mt", longOpt = "multi-thread", description = "concurrent process, default is 4 thread")
    private int multiThread = 4;

    @Opt(opt = "fl", longOpt = "file-list", description = "a file contains a list of dex to process")
    private Path fileList;

    @Opt(opt = "dsn", longOpt = "dont-sanitize-names", hasArg = false, description = "do not replace '_' by '-'")
    private boolean dontSanitizeNames = false;

    @Override
    protected void doCommandLine() throws Exception {
        List<String> f = new ArrayList<>(Arrays.asList(remainingArgs));
        if (fileList != null) {
            f.addAll(Files.readAllLines(fileList, StandardCharsets.UTF_8));
        }
        if (f.isEmpty()) {
            throw new HelpException();
        }

        if (multiThread < 1) throw new HelpException("Thread count must be positive");
        ExecutorService workers = Executors.newFixedThreadPool(multiThread);
        Exception failure = null;
        try {
            for (String fileName : f) {
                Path input = new File(fileName).toPath();
                Path output = new File(".").toPath().resolve(getBaseName(input) + "-dex2jar.jar");
                BaksmaliBaseDexExceptionHandler handler = new BaksmaliBaseDexExceptionHandler();
                System.err.println("dex2jar " + fileName + " -> " + output);
                try {
                    Dex2jar.from(input.toFile())
                            .withExceptionHandler(handler).dontSanitizeNames(dontSanitizeNames)
                            .withExecutor(workers).to(output);
                } catch (Exception e) {
                    if (failure == null) failure = e;
                    e.printStackTrace(System.err);
                } finally {
                    if (handler.hasException()) handler.dump(new File(".").toPath()
                            .resolve(getBaseName(input) + "-error.zip"), originalArgs);
                }
            }
            if (failure != null) throw failure;
        } finally {
            workers.shutdown();
        }
    }
}
