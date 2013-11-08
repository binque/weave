/*
 * Copyright 2012-2013 Continuuity,Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.continuuity.weave.filesystem;

import com.google.common.collect.Lists;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collections;
import java.util.Deque;
import java.util.UUID;

/**
 * A concrete implementation of {@link Location} for the Local filesystem.
 */
final class LocalLocation implements Location {
  private final File file;

  /**
   * Constructs a LocalLocation.
   *
   * @param file to the file.
   */
  LocalLocation(File file) {
    this.file = file;
  }

  /**
   * Checks if the this location exists on local file system.
   *
   * @return true if found; false otherwise.
   * @throws java.io.IOException
   */
  @Override
  public boolean exists() throws IOException {
    return file.exists();
  }

  /**
   * @return An {@link java.io.InputStream} for this location on local filesystem.
   * @throws IOException
   */
  @Override
  public InputStream getInputStream() throws IOException {
    File parent = file.getParentFile();
    if (!parent.exists()) {
      parent.mkdirs();
    }
    return new FileInputStream(file);
  }

  /**
   * @return An {@link java.io.OutputStream} for this location on local filesystem.
   * @throws IOException
   */
  @Override
  public OutputStream getOutputStream() throws IOException {
    File parent = file.getParentFile();
    if (!parent.exists()) {
      parent.mkdirs();
    }
    return new FileOutputStream(file);
  }

  /**
   * Local location doesn't supports permission. It's the same as calling {@link #getOutputStream()}.
   */
  @Override
  public OutputStream getOutputStream(String permission) throws IOException {
    return getOutputStream();
  }

  /**
   * @return Returns the name of the file or directory denoteed by this abstract pathname.
   */
  @Override
  public String getName() {
    return file.getName();
  }

  @Override
  public boolean createNew() throws IOException {
    return file.createNewFile();
  }

  /**
   * Appends the child to the current {@link Location} on local filesystem.
   * <p>
   * Returns a new instance of Location.
   * </p>
   *
   * @param child to be appended to this location.
   * @return A new instance of {@link Location}
   * @throws IOException
   */
  @Override
  public Location append(String child) throws IOException {
    return new LocalLocation(new File(file, child));
  }

  @Override
  public Location getTempFile(String suffix) throws IOException {
    return new LocalLocation(
      new File(file.getAbsolutePath() + "." + UUID.randomUUID() + (suffix == null ? TEMP_FILE_SUFFIX : suffix)));
  }

  /**
   * @return A {@link URI} for this location on local filesystem.
   */
  @Override
  public URI toURI() {
    return file.toURI();
  }

  /**
   * Deletes the file or directory denoted by this abstract pathname. If this
   * pathname denotes a directory, then the directory must be empty in order
   * to be deleted.
   *
   * @return true if and only if the file or directory is successfully delete; false otherwise.
   */
  @Override
  public boolean delete() throws IOException {
    return file.delete();
  }

  @Override
  public boolean delete(boolean recursive) throws IOException {
    if (!recursive) {
      return delete();
    }

    Deque<File> stack = Lists.newLinkedList();
    stack.add(file);
    while (!stack.isEmpty()) {
      File f = stack.peekLast();
      File[] files = f.listFiles();

      if (files != null && files.length != 0) {
        Collections.addAll(stack, files);
      } else {
        if (!f.delete()) {
          return false;
        }
        stack.pollLast();
      }
    }
    return true;
  }

  @Override
  public Location renameTo(Location destination) throws IOException {
    // destination will always be of the same type as this location
    boolean success = file.renameTo(((LocalLocation) destination).file);
    if (success) {
      return new LocalLocation(((LocalLocation) destination).file);
    } else {
      return null;
    }
  }

  /**
   * Creates the directory named by this abstract pathname, including any necessary
   * but nonexistent parent directories.
   *
   * @return true if and only if the renaming succeeded; false otherwise
   */
  @Override
  public boolean mkdirs() throws IOException {
    return file.mkdirs();
  }

  /**
   * @return Length of file.
   */
  @Override
  public long length() throws IOException {
    return file.length();
  }

  @Override
  public long lastModified() {
    return file.lastModified();
  }
}
