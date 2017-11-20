package com.julienviet.releaser;

import java.util.List;

public interface ProxyMBean {

  String getProfileId();

  int getPort();

  String getRepositoryId();

  int getSentCount();

  int getInProgressCount();

  int getErrorCount();

  List<String> getInProgress();

  Throwable getLastError();

}
