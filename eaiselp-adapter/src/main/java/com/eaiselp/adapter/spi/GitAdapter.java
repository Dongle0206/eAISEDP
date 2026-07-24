package com.eaiselp.adapter.spi;

import java.util.List;

public interface GitAdapter extends Adapter {
    String readFile(String repo, String path);
    void writeFile(String repo, String path, String content);
    boolean exists(String repo, String path);
    List<String> listFiles(String repo, String pathPattern);
    String commit(String repo, String message, List<String> files);
    void pull(String repo);
    String getCurrentCommit(String repo);
}
