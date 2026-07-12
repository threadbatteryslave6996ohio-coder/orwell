package dev.orwell.loganalyzer;

import java.io.IOException;

interface Model {
    boolean isEnabled();

    String prompt(String content) throws IOException, InterruptedException;
}
