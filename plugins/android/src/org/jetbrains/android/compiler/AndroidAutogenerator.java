package org.jetbrains.android.compiler;

import com.android.AndroidConstants;
import com.android.sdklib.IAndroidTarget;
import com.intellij.compiler.impl.ModuleCompileScope;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import org.jetbrains.android.compiler.tools.AndroidApt;
import org.jetbrains.android.compiler.tools.AndroidIdl;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidRootUtil;
import org.jetbrains.android.fileTypes.AndroidIdlFileType;
import org.jetbrains.android.fileTypes.AndroidRenderscriptFileType;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * @author Eugene.Kudelevsky
 */
public class AndroidAutogenerator {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.compiler.AndroidAutogenerator");

  private AndroidAutogenerator() {
  }

  public static void run(@NotNull AndroidAutogeneratorMode mode, @NotNull AndroidFacet facet, @NotNull CompileContext context) {
    switch (mode) {
      case AAPT:
        runAapt(facet, context);
        break;
      case AIDL:
        runAidl(facet, context);
        break;
      case RENDERSCRIPT:
        runRenderscript(facet, context);
        break;
    }
  }

  public static void runAapt(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();

    final AptAutogenerationItem item = ApplicationManager.getApplication().runReadAction(new Computable<AptAutogenerationItem>() {
      @Nullable
      @Override
      public AptAutogenerationItem compute() {
        if (module.isDisposed() || module.getProject().isDisposed()) {
          return null;
        }

        final AndroidPlatform platform = facet.getConfiguration().getAndroidPlatform();
        if (platform == null) {
          context.addMessage(CompilerMessageCategory.ERROR,
                             AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
          return null;
        }

        final IAndroidTarget target = platform.getTarget();
        final int platformToolsRevision = platform.getSdk().getPlatformToolsRevision();
        final String[] resPaths = AndroidCompileUtil.collectResourceDirs(facet, false, context);

        if (resPaths.length == 0) {
          return null;
        }

        final VirtualFile manifestFile = AndroidRootUtil.getManifestFileForCompiler(facet);
        if (manifestFile == null) {
          context.addMessage(CompilerMessageCategory.ERROR, AndroidBundle.message("android.compilation.error.manifest.not.found"),
                             null, -1, -1);
          return null;
        }

        final Manifest manifest = AndroidUtils.loadDomElement(module, manifestFile, Manifest.class);
        if (manifest == null) {
          context.addMessage(CompilerMessageCategory.ERROR, "Cannot parse file", manifestFile.getUrl(), -1, -1);
          return null;
        }

        String packageName = manifest.getPackage().getValue();
        if (packageName != null) {
          packageName = packageName.trim();
        }

        if (packageName == null || packageName.length() <= 0) {
          context.addMessage(CompilerMessageCategory.ERROR, AndroidBundle.message("package.not.found.error"), manifestFile.getUrl(),
                             -1, -1);
          return null;
        }

        final String sourceRootPath = facet.getAptGenSourceRootPath();
        if (sourceRootPath == null) {
          context.addMessage(CompilerMessageCategory.ERROR,
                             AndroidBundle.message("android.compilation.error.apt.gen.not.specified", module.getName()),
                             null, -1, -1);
          return null;
        }

        final String[] libPackages = getLibPackages(module, packageName);
        final Map<String, String> genFilePath2Package = new HashMap<String, String>();

        final String packageDir = sourceRootPath + '/' + packageName.replace('.', '/') + '/';
        genFilePath2Package.put(packageDir + AndroidCommonUtils.MANIFEST_JAVA_FILE_NAME, packageName);
        genFilePath2Package.put(packageDir + AndroidCommonUtils.R_JAVA_FILENAME, packageName);

        for (String libPackage : libPackages) {
          final String libPackageDir = sourceRootPath + '/' + libPackage.replace('.', '/') + '/';
          genFilePath2Package.put(libPackageDir + AndroidCommonUtils.MANIFEST_JAVA_FILE_NAME, packageName);
          genFilePath2Package.put(libPackageDir + AndroidCommonUtils.R_JAVA_FILENAME, packageName);
        }

        final String manifestFileOsPath = FileUtil.toSystemDependentName(manifestFile.getPath());

        return new AptAutogenerationItem(target, platformToolsRevision, manifestFileOsPath, packageName, sourceRootPath, resPaths,
                                         libPackages, facet.getConfiguration().LIBRARY_PROJECT, genFilePath2Package);
      }
    });

    if (item == null) {
      return;
    }

    final Set<VirtualFile> filesToCheck = new HashSet<VirtualFile>();

    for (String genFilePath : item.myGenFile2package.keySet()) {
      if (new File(genFilePath).exists()) {
        final VirtualFile genFile = LocalFileSystem.getInstance().findFileByPath(genFilePath);

        if (genFile != null) {
          filesToCheck.add(genFile);
        }
      }
    }

    if (!ensureFilesWritable(module.getProject(), filesToCheck)) {
      return;
    }

    try {
      final Map<CompilerMessageCategory, List<String>> messages = AndroidCompileUtil.toCompilerMessageCategoryKeys(
        AndroidApt.compile(item.myTarget, item.myPlatformToolsRevision, item.myManifestFileOsPath, item.myPackage,
                           item.myOutputDirOsPath, item.myResDirOsPaths, item.myLibPackages, item.myLibrary));

      AndroidCompileUtil.addMessages(context, messages);

      for (Map.Entry<String, String> entry : item.myGenFile2package.entrySet()) {
        final String path = entry.getKey();
        final String aPackage = entry.getValue();
        removeDuplicateClasses(module, aPackage, new File(path), item.myOutputDirOsPath);
      }

      final VirtualFile genSourceRoot = LocalFileSystem.getInstance().findFileByPath(item.myOutputDirOsPath);
      if (genSourceRoot != null) {
        genSourceRoot.refresh(false, true);
      }
    }
    catch (final IOException e) {
      LOG.info(e);
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          if (module.getProject().isDisposed()) return;
          context.addMessage(CompilerMessageCategory.ERROR, "I/O error: " + e.getMessage(), null, -1, -1);
        }
      });
    }
  }

  public static void runAidl(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();
    final ModuleCompileScope moduleCompileScope = new ModuleCompileScope(module, false);
    final VirtualFile[] files = moduleCompileScope.getFiles(AndroidIdlFileType.ourFileType, true);
    final List<IdlAutogenerationItem> items = new ArrayList<IdlAutogenerationItem>();

    for (final VirtualFile file : files) {
      final IdlAutogenerationItem item = ApplicationManager.getApplication().runReadAction(new Computable<IdlAutogenerationItem>() {
        @Nullable
        @Override
        public IdlAutogenerationItem compute() {
          if (module.isDisposed() || module.getProject().isDisposed()) {
            return null;
          }

          final IAndroidTarget target = facet.getConfiguration().getAndroidTarget();
          if (target == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
            return null;
          }

          final String packageName = AndroidUtils.getPackageName(module, file);
          if (packageName == null) {
            context.addMessage(CompilerMessageCategory.ERROR, "Cannot compute package for file", file.getUrl(), -1, -1);
            return null;
          }

          final String sourceRootPath = facet.getAidlGenSourceRootPath();
          if (sourceRootPath == null) {
            context.addMessage(CompilerMessageCategory.ERROR,
                               AndroidBundle.message("android.compilation.error.apt.gen.not.specified", module.getName()), null, -1, -1);
            return null;
          }

          final VirtualFile[] sourceRoots = AndroidPackagingCompiler.getSourceRootsForModuleAndDependencies(module, false);
          final String[] sourceRootOsPaths = AndroidCompileUtil.toOsPaths(sourceRoots);

          final String outFileOsPath = FileUtil.toSystemDependentName(
            sourceRootPath + '/' + packageName.replace('.', '/') + '/' + file.getNameWithoutExtension() + ".java");

          return new IdlAutogenerationItem(file, target, outFileOsPath, sourceRootOsPaths, sourceRootPath, packageName);
        }
      });

      if (item != null) {
        items.add(item);
      }
    }

    final Set<VirtualFile> filesToCheck = new HashSet<VirtualFile>();

    for (IdlAutogenerationItem item : items) {
      if (new File(FileUtil.toSystemDependentName(item.myFile.getPath())).exists()) {
        filesToCheck.add(item.myFile);
      }
    }

    if (!ensureFilesWritable(module.getProject(), filesToCheck)) {
      return;
    }

    for (IdlAutogenerationItem item : items) {
      final VirtualFile file = item.myFile;
      final String fileOsPath = FileUtil.toSystemDependentName(file.getPath());

      try {
        final Map<CompilerMessageCategory, List<String>> messages = AndroidCompileUtil.toCompilerMessageCategoryKeys(
          AndroidIdl.execute(item.myTarget, fileOsPath, item.myOutFileOsPath, item.mySourceRootOsPaths));

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) return;

            for (CompilerMessageCategory category : messages.keySet()) {
              List<String> messageList = messages.get(category);
              for (String message : messageList) {
                context.addMessage(category, message, file.getUrl(), -1, -1);
              }
            }
          }
        });

        removeDuplicateClasses(module, item.myPackage, new File(item.myOutFileOsPath), item.myOutDirOsPath);

        final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(item.myOutDirOsPath);
        if (genDir != null) {
          genDir.refresh(false, true);
        }
      }
      catch (final IOException e) {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) return;
            context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), file.getUrl(), -1, -1);
          }
        });
      }
    }
  }

  private static void runRenderscript(@NotNull final AndroidFacet facet, @NotNull final CompileContext context) {
    final Module module = facet.getModule();

    final ModuleCompileScope moduleCompileScope = new ModuleCompileScope(module, false);
    final VirtualFile[] files = moduleCompileScope.getFiles(AndroidRenderscriptFileType.INSTANCE, true);

    for (final VirtualFile file : files) {

      final RenderscriptAutogenerationItem item =
        ApplicationManager.getApplication().runReadAction(new Computable<RenderscriptAutogenerationItem>() {
          @Nullable
          @Override
          public RenderscriptAutogenerationItem compute() {
            final AndroidPlatform platform = facet.getConfiguration().getAndroidPlatform();
            if (platform == null) {
              context.addMessage(CompilerMessageCategory.ERROR,
                                 AndroidBundle.message("android.compilation.error.specify.platform", module.getName()), null, -1, -1);
              return null;
            }

            final IAndroidTarget target = platform.getTarget();
            final String sdkLocation = platform.getSdk().getLocation();

            final String packageName = AndroidUtils.getPackageName(module, file);
            if (packageName == null) {
              context.addMessage(CompilerMessageCategory.ERROR, "Cannot compute package for file", file.getUrl(), -1, -1);
              return null;
            }

            final String resourceDirPath = AndroidRootUtil.getResourceDirPath(facet);
            assert resourceDirPath != null;

            final String sourceRootPath = AndroidRootUtil.getRenderscriptGenSourceRootPath(module);
            if (sourceRootPath == null) {
              return null;
            }

            final String rawDirPath = resourceDirPath + '/' + AndroidConstants.FD_RES_RAW;

            return new RenderscriptAutogenerationItem(sdkLocation, target, sourceRootPath, rawDirPath);
          }
        });

      try {
        final Map<CompilerMessageCategory, List<String>> messages = AndroidRenderscriptCompiler.
          launchRenderscriptCompiler(module.getProject(), item.mySdkLocation, item.myTarget, file, item.myGenDirPath, item.myRawDirPath);

        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) {
              return;
            }

            for (final CompilerMessageCategory category : messages.keySet()) {
              final List<String> messageList = messages.get(category);
              for (final String message : messageList) {
                context.addMessage(category, message, file.getUrl(), -1, -1);
              }
            }
          }
        });

        final VirtualFile genDir = LocalFileSystem.getInstance().findFileByPath(item.myGenDirPath);
        if (genDir != null) {
          genDir.refresh(false, true);
        }
      }
      catch (final IOException e) {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          public void run() {
            if (module.getProject().isDisposed()) return;
            context.addMessage(CompilerMessageCategory.ERROR, e.getMessage(), file.getUrl(), -1, -1);
          }
        });
      }
    }
  }

  private static boolean ensureFilesWritable(@NotNull final Project project, @NotNull final Collection<VirtualFile> filesToCheck) {
    if (filesToCheck.size() == 0) {
      return true;
    }
    final boolean[] run = {false};

    ApplicationManager.getApplication().invokeAndWait(new Runnable() {
      @Override
      public void run() {
        ApplicationManager.getApplication().runReadAction(new Runnable() {
          @Override
          public void run() {
            run[0] = !project.isDisposed() &&
                     ReadonlyStatusHandler.ensureFilesWritable(project, filesToCheck.toArray(new VirtualFile[filesToCheck.size()]));
          }
        });
      }
    }, ModalityState.defaultModalityState());

    return run[0];
  }

  private static void removeDuplicateClasses(@NotNull final Module module,
                                             @NotNull final String aPackage,
                                             @NotNull final File generatedFile,
                                             @NotNull final String sourceRootPath) {
    if (generatedFile.exists()) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          if (module.getProject().isDisposed() || module.isDisposed()) {
            return;
          }
          String className = FileUtil.getNameWithoutExtension(generatedFile);
          AndroidCompileUtil.removeDuplicatingClasses(module, aPackage, className, generatedFile, sourceRootPath);
        }
      });
    }
  }

  @NotNull
  private static String[] getLibPackages(@NotNull Module module, @NotNull String packageName) {
    final Set<String> packageSet = new HashSet<String>();
    packageSet.add(packageName);

    final List<String> result = new ArrayList<String>();

    for (String libPackage : AndroidUtils.getDepLibsPackages(module)) {
      if (packageSet.add(libPackage)) {
        result.add(libPackage);
      }
    }

    return ArrayUtil.toStringArray(result);
  }

  private static class AptAutogenerationItem {
    final IAndroidTarget myTarget;
    final int myPlatformToolsRevision;
    final String myManifestFileOsPath;
    final String myPackage;
    final String myOutputDirOsPath;
    final String[] myResDirOsPaths;
    final String[] myLibPackages;
    final boolean myLibrary;
    final Map<String, String> myGenFile2package;

    private AptAutogenerationItem(@NotNull IAndroidTarget target,
                                  int platformToolsRevision,
                                  @NotNull String manifestFileOsPath,
                                  @NotNull String aPackage,
                                  @NotNull String outputDirOsPath,
                                  @NotNull String[] resDirOsPaths,
                                  @NotNull String[] libPackages,
                                  boolean library,
                                  @NotNull Map<String, String> genFile2package) {
      myTarget = target;
      myPlatformToolsRevision = platformToolsRevision;
      myManifestFileOsPath = manifestFileOsPath;
      myPackage = aPackage;
      myOutputDirOsPath = outputDirOsPath;
      myResDirOsPaths = resDirOsPaths;
      myLibPackages = libPackages;
      myLibrary = library;
      myGenFile2package = genFile2package;
    }
  }

  private static class IdlAutogenerationItem {
    final VirtualFile myFile;
    final IAndroidTarget myTarget;
    final String myOutFileOsPath;
    final String[] mySourceRootOsPaths;
    final String myOutDirOsPath;
    final String myPackage;

    private IdlAutogenerationItem(@NotNull VirtualFile file,
                                  @NotNull IAndroidTarget target,
                                  @NotNull String outFileOsPath,
                                  @NotNull String[] sourceRootOsPaths,
                                  @NotNull String outDirOsPath,
                                  @NotNull String aPackage) {
      myFile = file;
      myTarget = target;
      myOutFileOsPath = outFileOsPath;
      mySourceRootOsPaths = sourceRootOsPaths;
      myOutDirOsPath = outDirOsPath;
      myPackage = aPackage;
    }
  }

  private static class RenderscriptAutogenerationItem {
    final String mySdkLocation;
    final IAndroidTarget myTarget;
    final String myGenDirPath;
    final String myRawDirPath;

    private RenderscriptAutogenerationItem(@NotNull String sdkLocation,
                                           @NotNull IAndroidTarget target,
                                           @NotNull String genDirPath,
                                           @NotNull String rawDirPath) {
      mySdkLocation = sdkLocation;
      myTarget = target;
      myGenDirPath = genDirPath;
      myRawDirPath = rawDirPath;
    }
  }
}
