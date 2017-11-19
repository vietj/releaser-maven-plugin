package com.julienviet.releaser;

public class ProxyOptions {

  private String stagingHost = "oss.sonatype.org";
  private int stagingPort = 443;
  private boolean stagingSsl = true;
  private boolean stagingPipelining = true;
  private boolean stagingKeepAlive = true;
  private int stagingPipeliningLimit = 10;
  private int stagingMaxPoolSize = 5;
  private String stagingProfileId;
  private String stagingUsername;
  private String stagingPassword;
  private int port;
  private String repositoryId;

  public String getStagingHost() {
    return stagingHost;
  }

  public ProxyOptions setStagingHost(String stagingHost) {
    this.stagingHost = stagingHost;
    return this;
  }

  public int getStagingPort() {
    return stagingPort;
  }

  public ProxyOptions setStagingPort(int stagingPort) {
    this.stagingPort = stagingPort;
    return this;
  }

  public boolean isStagingSsl() {
    return stagingSsl;
  }

  public ProxyOptions setStagingSsl(boolean stagingSsl) {
    this.stagingSsl = stagingSsl;
    return this;
  }

  public boolean isStagingPipelining() {
    return stagingPipelining;
  }

  public ProxyOptions setStagingPipelining(boolean stagingPipelining) {
    this.stagingPipelining = stagingPipelining;
    return this;
  }

  public boolean isStagingKeepAlive() {
    return stagingKeepAlive;
  }

  public ProxyOptions setStagingKeepAlive(boolean stagingKeepAlive) {
    this.stagingKeepAlive = stagingKeepAlive;
    return this;
  }

  public int getStagingPipeliningLimit() {
    return stagingPipeliningLimit;
  }

  public ProxyOptions setStagingPipeliningLimit(int stagingPipeliningLimit) {
    this.stagingPipeliningLimit = stagingPipeliningLimit;
    return this;
  }

  public int getStagingMaxPoolSize() {
    return stagingMaxPoolSize;
  }

  public ProxyOptions setStagingMaxPoolSize(int stagingMaxPoolSize) {
    this.stagingMaxPoolSize = stagingMaxPoolSize;
    return this;
  }

  public String getStagingProfileId() {
    return stagingProfileId;
  }

  public ProxyOptions setStagingProfileId(String stagingProfileId) {
    this.stagingProfileId = stagingProfileId;
    return this;
  }

  public String getStagingUsername() {
    return stagingUsername;
  }

  public ProxyOptions setStagingUsername(String stagingUsername) {
    this.stagingUsername = stagingUsername;
    return this;
  }

  public String getStagingPassword() {
    return stagingPassword;
  }

  public ProxyOptions setStagingPassword(String stagingPassword) {
    this.stagingPassword = stagingPassword;
    return this;
  }

  public int getPort() {
    return port;
  }

  public ProxyOptions setPort(int port) {
    this.port = port;
    return this;
  }

  public String getRepositoryId() {
    return repositoryId;
  }

  public ProxyOptions setRepositoryId(String repositoryId) {
    this.repositoryId = repositoryId;
    return this;
  }
}
