/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.container.internal;

import static java.io.File.pathSeparatorChar;
import static java.lang.String.format;
import static java.lang.System.getProperties;
import static java.lang.System.getProperty;
import static java.util.regex.Pattern.compile;
import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.slf4j.LoggerFactory.getLogger;

import org.mule.runtime.module.artifact.api.classloader.ExportedService;

import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Explores the content of the JRE used to run the container.
 */
public final class JreExplorer {

  private static final Logger LOGGER = getLogger(JreExplorer.class);

  private static final String META_INF_SERVICES_PATH = "META-INF/services/";
  private static final Pattern SLASH_PATTERN = compile("/");

  private static Object bootLayer;
  private static Method getModulePackagesMethod;
  private static Method getLayerModulesMethod;
  private static boolean isRequiredReflectionDataPresent;

  static {
    try {
      Class moduleLayerClass = Class.forName("java.lang.ModuleLayer");
      Method getBootLayerMethod = moduleLayerClass.getDeclaredMethod("boot");
      getLayerModulesMethod = moduleLayerClass.getDeclaredMethod("modules");

      Class moduleClass = Class.forName("java.lang.Module");
      getModulePackagesMethod = moduleClass.getDeclaredMethod("getPackages");

      bootLayer = getBootLayerMethod.invoke(null);

      isRequiredReflectionDataPresent = true;
    } catch (ClassNotFoundException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
      if (LOGGER.isDebugEnabled()) {
        LOGGER
            .debug("Error found when trying to access to Java 9+ classes via reflection. If running with Java 8 or older this is the expected behaviour");
      }
      isRequiredReflectionDataPresent = false;
    }
  }

  private JreExplorer() {}

  /**
   * Explores the content of the JRE being used
   *
   * @param packages will store the Java packages found on the environment. Non null.
   * @param resources will store the resources found on the environment. Non null.
   * @param services will store the services defined via SPI found on the environment. Non null.
   */
  public static void exploreJdk(final Set<String> packages, Set<String> resources, List<ExportedService> services) {
    List<String> jdkPaths = new ArrayList<>();

    // These are present in JDK 8
    addJdkPath(jdkPaths, "sun.boot.class.path");
    addJdkPath(jdkPaths, "java.ext.dirs");

    // These is present in JDK 9, 10, 11
    addJdkPath(jdkPaths, "sun.boot.library.path");

    if (jdkPaths.isEmpty()) {
      LOGGER.warn("No JDK path/dir system property found. Defaulting to the whole classpath."
          + " This may cause classloading issues in some plugins.");
      jdkPaths.add(getProperty("java.class.path"));
    }

    explorePaths(jdkPaths, packages, resources, services);
    exploreJdkModules(packages);
  }

  private static void addJdkPath(List<String> jdkPaths, String key) {
    if (getProperties().containsKey(key)) {
      jdkPaths.add(getProperty(key));
    }
  }

  /**
   * Explores the provided paths searching for Java packages, resources and SPI service definitions
   *
   * @param jdkPaths paths to explore. Non null.
   * @param packages will store the Java packages found on the environment. Non null.
   * @param resources will store the resources found on the environment. Non null.
   * @param services will store the services defined via SPI found on the environment. Non null.
   */
  static void explorePaths(final List<String> jdkPaths, final Set<String> packages, Set<String> resources,
                           List<ExportedService> services) {
    checkArgument(jdkPaths != null && !jdkPaths.isEmpty(), "jdkPaths cannot be empty");

    for (String jdkPath : jdkPaths) {
      explorePath(packages, resources, services, jdkPath);
    }
  }

  /**
   * Search for packages using the JRE's module architecture. (Java 9 and above).
   * <p/>
   * We need to use reflection because we may be running with JDK 8 or older.
   *
   * @param packages where to add new found packages
   */
  private static void exploreJdkModules(Set<String> packages) {
    if (isRequiredReflectionDataPresent) {
      try {
        Set modules = (Set) getLayerModulesMethod.invoke(bootLayer);
        modules.stream().forEach(module -> {
          try {
            packages.addAll((Set) getModulePackagesMethod.invoke(module));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
      } catch (IllegalAccessException | InvocationTargetException e) {
        throw new RuntimeException("Error trying to get packages from JDK modules", e);
      }
    }
  }

  private static void explorePath(Set<String> packages, Set<String> resources, List<ExportedService> services, String jdkPath) {
    int fromIndex = 0;
    int endIndex;

    do {
      endIndex = jdkPath.indexOf(pathSeparatorChar, fromIndex);
      String item = endIndex == -1 ? jdkPath.substring(fromIndex) : jdkPath.substring(fromIndex, endIndex);

      final File file = new File(item);
      if (file.exists()) {
        if (file.isDirectory()) {
          exploreDirectory(packages, resources, services, file);
        } else {
          try {
            exploreJar(packages, resources, services, file);
          } catch (IOException e) {
            throw new IllegalStateException(createJarExploringError(file), e);
          }
        }
      }
      fromIndex = endIndex + 1;
    } while (endIndex != -1);
  }

  private static void exploreJar(Set<String> packages, Set<String> resources, List<ExportedService> services, File file)
      throws IOException {
    final ZipFile zipFile = new ZipFile(file);

    try {
      final Enumeration<? extends ZipEntry> entries = zipFile.entries();

      while (entries.hasMoreElements()) {
        final ZipEntry entry = entries.nextElement();
        final String name = entry.getName();
        final int lastSlash = name.lastIndexOf('/');
        if (lastSlash != -1 && name.endsWith(".class")) {
          packages.add(SLASH_PATTERN.matcher(name.substring(0, lastSlash)).replaceAll("."));
        } else if (!entry.isDirectory()) {
          if (name.startsWith(META_INF_SERVICES_PATH)) {
            String serviceInterface = name.substring(META_INF_SERVICES_PATH.length());
            URL resource = getServiceResourceUrl(file.toURI().toURL(), name);

            services.add(new ExportedService(serviceInterface, resource));
          } else {
            resources.add(name);
          }
        }
      }
    } finally {
      if (zipFile != null)
        try {
          zipFile.close();
        } catch (Throwable ignored) {
        }
    }
  }

  private static void exploreDirectory(final Set<String> packages, Set<String> resources, List<ExportedService> services,
                                       final File file) {
    File[] content = file.listFiles();
    if (content == null) {
      return;
    }

    for (File entry : content) {
      if (entry.exists()) {
        if (entry.isDirectory()) {
          exploreDirectory(packages, resources, services, entry);
        } else if (entry.getName().endsWith(".jar")) {
          try {
            exploreJar(packages, resources, services, entry);
          } catch (IOException e) {
            throw new IllegalStateException(createJarExploringError(entry), e);
          }
        }
      }
    }
  }

  private static String createJarExploringError(File file) {
    return format("Unable to explore '%s'", file.getAbsoluteFile());
  }

  static URL getServiceResourceUrl(URL resource, String serviceInterface) throws MalformedURLException {
    return new URL("jar:" + resource + "!/" + serviceInterface);
  }
}
