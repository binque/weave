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
package com.continuuity.weave.internal;

import com.continuuity.weave.filesystem.Location;
import com.continuuity.weave.internal.utils.Dependencies;
import com.google.common.base.Function;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;

/**
 * This class builds jar files based on class dependencies.
 */
public final class ApplicationBundler {

  private final List<String> excludePackages;
  private final List<String> includePackages;
  private final Set<String> bootstrapClassPaths;
  private final CRC32 crc32;

  /**
   * Constructs a ApplicationBundler.
   *
   * @param excludePackages Class packages to exclude
   */
  public ApplicationBundler(Iterable<String> excludePackages) {
    this(excludePackages, ImmutableList.<String>of());
  }

  /**
   * Constructs a ApplicationBundler.
   *
   * @param excludePackages Class packages to exclude
   * @param includePackages Class packages that should be included. Anything in this list will override the
   *                        one provided in excludePackages.
   */
  public ApplicationBundler(Iterable<String> excludePackages, Iterable<String> includePackages) {
    this.excludePackages = ImmutableList.copyOf(excludePackages);
    this.includePackages = ImmutableList.copyOf(includePackages);

    ImmutableSet.Builder<String> builder = ImmutableSet.builder();
    for (String classpath : Splitter.on(File.pathSeparatorChar).split(System.getProperty("sun.boot.class.path"))) {
      File file = new File(classpath);
      builder.add(file.getAbsolutePath());
      try {
        builder.add(file.getCanonicalPath());
      } catch (IOException e) {
        // Ignore the exception and proceed.
      }
    }
    this.bootstrapClassPaths = builder.build();
    this.crc32 = new CRC32();

  }

  public void createBundle(Location target, Iterable<Class<?>> classes) throws IOException {
    createBundle(target, classes, ImmutableList.<URI>of());
  }

  /**
   * Same as calling {@link #createBundle(Location, Iterable)}.
   */
  public void createBundle(Location target, Class<?> clz, Class<?>...classes) throws IOException {
    createBundle(target, ImmutableSet.<Class<?>>builder().add(clz).add(classes).build());
  }

  /**
   * Creates a jar file which includes all the given classes and all the classes that they depended on.
   * The jar will also include all classes and resources under the packages as given as include packages
   * in the constructor.
   *
   * @param target Where to save the target jar file.
   * @param resources Extra resources to put into the jar file. If resource is a jar file, it'll be put under
   *                  lib/ entry, otherwise under the resources/ entry.
   * @param classes Set of classes to start the dependency traversal.
   * @throws IOException
   */
  public void createBundle(Location target, Iterable<Class<?>> classes, Iterable<URI> resources) throws IOException {
    // Write the jar to local tmp file first
    File tmpJar = File.createTempFile(target.getName(), ".tmp");
    try {
      Set<String> entries = Sets.newHashSet();
      JarOutputStream jarOut = new JarOutputStream(new FileOutputStream(tmpJar));
      try {
        // Find class dependencies
        findDependencies(classes, entries, jarOut);

        // Add extra resources
        for (URI resource : resources) {
          copyResource(resource, entries, jarOut);
        }
      } finally {
        jarOut.close();
      }
      // Copy the tmp jar into destination.
      OutputStream os = new BufferedOutputStream(target.getOutputStream());
      try {
        Files.copy(tmpJar, os);
      } finally {
        os.close();
      }
    } finally {
      tmpJar.delete();
    }
  }

  private void findDependencies(Iterable<Class<?>> classes, final Set<String> entries,
                                final JarOutputStream jarOut) throws IOException {

    Iterable<String> classNames = Iterables.transform(classes, new Function<Class<?>, String>() {
      @Override
      public String apply(Class<?> input) {
        return input.getName();
      }
    });

    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      classLoader = getClass().getClassLoader();
    }
    Dependencies.findClassDependencies(classLoader, new Dependencies.ClassAcceptor() {
      @Override
      public boolean accept(String className, URL classUrl, URL classPathUrl) {
        if (bootstrapClassPaths.contains(classPathUrl.getFile())) {
          return false;
        }

        boolean shouldInclude = false;
        for (String include : includePackages) {
          if (className.startsWith(include)) {
            shouldInclude = true;
            break;
          }
        }

        if (!shouldInclude) {
          for (String exclude : excludePackages) {
            if (className.startsWith(exclude)) {
              return false;
            }
          }
        }

        putEntry(className, classUrl, classPathUrl, entries, jarOut);
        return true;
      }
    }, classNames);
  }

  private void putEntry(String className, URL classUrl, URL classPathUrl, Set<String> entries, JarOutputStream jarOut) {
    String classPath = classPathUrl.getFile();
    if (classPath.endsWith(".jar")) {
      saveDirEntry("lib/", entries, jarOut);
      saveEntry("lib/" + classPath.substring(classPath.lastIndexOf('/') + 1), classPathUrl, entries, jarOut, false);
    } else {
      // Class file, put it under the classes directory
      saveDirEntry("classes/", entries, jarOut);
      if ("file".equals(classPathUrl.getProtocol())) {
        // Copy every files under the classPath
        try {
          copyDir(new File(classPathUrl.toURI()), "classes/", entries, jarOut);
        } catch (Exception e) {
          throw Throwables.propagate(e);
        }
      } else {
        String entry = "classes/" + className.replace('.', '/') + ".class";
        saveDirEntry(entry.substring(0, entry.lastIndexOf('/') + 1), entries, jarOut);
        saveEntry(entry, classUrl, entries, jarOut, true);
      }
    }
  }

  /**
   * Saves a directory entry to the jar output.
   */
  private void saveDirEntry(String path, Set<String> entries, JarOutputStream jarOut) {
    if (entries.contains(path)) {
      return;
    }

    try {
      String entry = "";
      for (String dir : Splitter.on('/').omitEmptyStrings().split(path)) {
        entry += dir + '/';
        if (entries.add(entry)) {
          JarEntry jarEntry = new JarEntry(entry);
          jarEntry.setMethod(JarOutputStream.STORED);
          jarEntry.setSize(0L);
          jarEntry.setCrc(0L);
          jarOut.putNextEntry(jarEntry);
          jarOut.closeEntry();
        }
      }
    } catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }

  /**
   * Saves a class entry to the jar output.
   */
  private void saveEntry(String entry, URL url, Set<String> entries, JarOutputStream jarOut, boolean compress) {
    if (!entries.add(entry)) {
      return;
    }
    try {
      JarEntry jarEntry = new JarEntry(entry);
      InputStream is = url.openStream();

      try {
        if (compress) {
          jarOut.putNextEntry(jarEntry);
          ByteStreams.copy(is, jarOut);
        } else {
          crc32.reset();
          TransferByteOutputStream os = new TransferByteOutputStream();
          CheckedOutputStream checkedOut = new CheckedOutputStream(os, crc32);
          ByteStreams.copy(is, checkedOut);
          checkedOut.close();

          long size = os.size();
          jarEntry.setMethod(JarEntry.STORED);
          jarEntry.setSize(size);
          jarEntry.setCrc(checkedOut.getChecksum().getValue());
          jarOut.putNextEntry(jarEntry);
          os.transfer(jarOut);
        }
      } finally {
        is.close();
      }
      jarOut.closeEntry();
    } catch (Exception e) {
      throw Throwables.propagate(e);
    }
  }


  /**
   * Copies all entries under the file path.
   */
  private void copyDir(File baseDir, String entryPrefix,
                       Set<String> entries, JarOutputStream jarOut) throws IOException {
    URI baseUri = baseDir.toURI();
    Queue<File> queue = Lists.newLinkedList();
    Collections.addAll(queue, baseDir.listFiles());
    while (!queue.isEmpty()) {
      File file = queue.remove();

      String entry = entryPrefix + baseUri.relativize(file.toURI()).getPath();
      if (entries.add(entry)) {
        jarOut.putNextEntry(new JarEntry(entry));
        if (file.isFile()) {
          Files.copy(file, jarOut);
        }
        jarOut.closeEntry();
      }

      if (file.isDirectory()) {
        File[] files = file.listFiles();
        if (files != null) {
          queue.addAll(Arrays.asList(files));
        }
      }
    }
  }

  private void copyResource(URI resource, Set<String> entries, JarOutputStream jarOut) throws IOException {
    if ("file".equals(resource.getScheme())) {
      File file = new File(resource);
      if (file.isDirectory()) {
        saveDirEntry("resources/", entries, jarOut);
        copyDir(file, "resources/", entries, jarOut);
        return;
      }
    }

    URL url = resource.toURL();
    String path = url.getFile();
    String prefix = path.endsWith(".jar") ? "lib/" : "resources/";
    path = prefix + path.substring(path.lastIndexOf('/') + 1);

    saveDirEntry(prefix, entries, jarOut);
    jarOut.putNextEntry(new JarEntry(path));
    InputStream is = url.openStream();
    try {
      ByteStreams.copy(is, jarOut);
    } finally {
      is.close();
    }
  }

  private static final class TransferByteOutputStream extends ByteArrayOutputStream {

    public void transfer(OutputStream os) throws IOException {
      os.write(buf, 0, count);
    }
  }
}
