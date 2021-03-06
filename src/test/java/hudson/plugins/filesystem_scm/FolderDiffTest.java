package hudson.plugins.filesystem_scm;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.SystemUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import hudson.plugins.filesystem_scm.FolderDiff.Entry;

public class FolderDiffTest {

    File src, dst;
    @Rule
    public TemporaryFolder srcFolder = new TemporaryFolder();
    @Rule
    public TemporaryFolder dstFolder = new TemporaryFolder();
    String rootFilePath, subfolderFilePath, folderFilePath;
    long currentTestExecutionTime;
    long ONE_MINUTE = 1000 * 60;

    @Before
    public void setupSrcAndDst() throws IOException {
        // setup src Folder
        src = srcFolder.getRoot();
        src.createNewFile();
        subfolderFilePath = createFile(src, "Folder", "subFolder", "subFolderFile.txt");
        folderFilePath = createFile(src, "Folder", "FolderFile.git");
        rootFilePath = createFile(src, "RootFile.java");

        // setup destination Folder
        dst = dstFolder.getRoot();
        dst.createNewFile();
        createFile(dst, subfolderFilePath);
        createFile(dst, folderFilePath);
        createFile(dst, rootFilePath);

        currentTestExecutionTime = System.currentTimeMillis();

    }

    private String createFile(File root, String... strings) throws IOException {
        String path = TestUtils.createPlatformDependendPath(strings);
        File file = new File(root, path);
        FileUtils.touch(file);
        Assert.assertTrue(file.exists());
        return path;
    }

    @Test
    public void getFiles2Delete_noRemovedSrcFiles_nothing2Delete() throws IOException, InterruptedException {
        assertMarkAsDelete(new HashSet<FolderDiff.Entry>(), src, dst);
    }

    @Test
    public void getFiles2Delete_allDestinationFolderDeleted_nothing2Delete() throws IOException, InterruptedException {
        FileUtils.deleteDirectory(dst);
        assertMarkAsDelete(new HashSet<FolderDiff.Entry>(), src, dst);
    }

    @Test
    public void getFiles2Delete_aSourceFolderDeleted_markAllFilesForDeletion()
            throws IOException, InterruptedException {
        FileUtils.deleteDirectory(src);
        Set<FolderDiff.Entry> expected = new HashSet<FolderDiff.Entry>();
        expected.add(new Entry(folderFilePath, FolderDiff.Entry.Type.DELETED));
        expected.add(new Entry(rootFilePath, FolderDiff.Entry.Type.DELETED));
        expected.add(new Entry(subfolderFilePath, FolderDiff.Entry.Type.DELETED));
        assertMarkAsDelete(expected, src, dst);
    }

    @Test
    public void getFilesNewOrModifiedFiles_noNewOrModifiedFilesLastBuildTimeNow_nothing2Add() throws IOException {
        assertMarkAsNewOrModified(new HashSet<FolderDiff.Entry>(), currentTestExecutionTime, src, dst);
    }

    @Test
    public void getFilesNewOrModifiedFiles_DestinationFolderDeleted_AllNewFiles() throws IOException {
        FileUtils.deleteDirectory(dst);
        Set<FolderDiff.Entry> expected = new HashSet<FolderDiff.Entry>();
        expected.add(new Entry(folderFilePath, FolderDiff.Entry.Type.NEW));
        expected.add(new Entry(rootFilePath, FolderDiff.Entry.Type.NEW));
        expected.add(new Entry(subfolderFilePath, FolderDiff.Entry.Type.NEW));
        assertMarkAsNewOrModified(expected, 0l, src, dst);
    }

    @Test(expected = IOException.class)
    public void getFilesNewOrModifiedFiles_SourceFolderDeleted_ExceptionThrown() throws IOException {
        FileUtils.deleteDirectory(src);
        assertMarkAsNewOrModified(new HashSet<FolderDiff.Entry>(), 0l, src, dst);
    }

    @Test
    public void getFilesNewOrModifiedFiles_SourceFileModificationDateNewerThenLastBuildTime_AddAllModifiedFiles()
            throws IOException {
        Set<FolderDiff.Entry> expected = new HashSet<FolderDiff.Entry>();
        expected.add(new Entry(folderFilePath, FolderDiff.Entry.Type.MODIFIED));
        expected.add(new Entry(rootFilePath, FolderDiff.Entry.Type.MODIFIED));
        expected.add(new Entry(subfolderFilePath, FolderDiff.Entry.Type.MODIFIED));
        assertMarkAsNewOrModified(expected, currentTestExecutionTime - ONE_MINUTE, src, dst);
    }

    @Test
    public void getFilesNewOrModifiedFiles_OneSourceFileNewerThanDestinationFile_SourceFileModified()
            throws IOException {
        Set<FolderDiff.Entry> expected = new HashSet<FolderDiff.Entry>();
        expected.add(new Entry(folderFilePath, FolderDiff.Entry.Type.MODIFIED));
        // implementation works only when times between file modification dates are at
        // least different by 1000 mys == 1 second
        // setModification Time in the future -> therfore the destination file needs to
        // be updated
        Assert.assertTrue((new File(src, folderFilePath)).setLastModified(currentTestExecutionTime + ONE_MINUTE));
        assertMarkAsNewOrModified(expected, currentTestExecutionTime, src, dst);
    }

    @Test
    public void getFilesNewOrModified_NewHiddenFileAndActOnHiddenFiles_HiddenFileIdentifiedAsNew() throws IOException {
        // setup
        String hiddenFilePath = createFile(src, "Folder", "subFolder", "._HiddenFile");
        hideFile(hiddenFilePath);

        Set<FolderDiff.Entry> expected = new HashSet<FolderDiff.Entry>();
        expected.add(new Entry(hiddenFilePath, FolderDiff.Entry.Type.NEW));
        // execute
        FolderDiffFake diff = getFolderDiff(src, dst);
        List<FolderDiff.Entry> actualResult = diff.getNewOrModifiedFiles(currentTestExecutionTime + ONE_MINUTE, false);
        // check
        assertMarkAsNewOrModified(expected, actualResult, diff);
    }

    @Test
    public void getFilesNewOrModified_NewHiddenFileButIgnoreHidden_NoNewFile() throws IOException {
        // setup
        String hiddenFilePathString = createFile(src, "Folder", "subFolder", "._HiddenFile");
        hideFile(hiddenFilePathString);
        // execute
        FolderDiffFake diff = getFolderDiff(src, dst);
        diff.setIgnoreHidden(true);
        List<FolderDiff.Entry> actualResult = diff.getNewOrModifiedFiles(currentTestExecutionTime + ONE_MINUTE, false);
        // check
        assertMarkAsNewOrModified(new HashSet<FolderDiff.Entry>(), actualResult, diff);
    }

    private void hideFile(String hiddenFilePathString) throws IOException {
        File hiddenFile = new File(src, hiddenFilePathString);
        // Windows specific code
        if (SystemUtils.IS_OS_WINDOWS) {
            Path hiddenFilePath = Paths.get(hiddenFile.getAbsolutePath());
            Files.setAttribute(hiddenFilePath, "dos:hidden", true);
        }
        Assert.assertTrue(hiddenFile.isHidden());
    }

    private void assertMarkAsNewOrModified(Set<FolderDiff.Entry> expected, List<FolderDiff.Entry> actual,
            FolderDiffFake diff) {
        assertEquals(expected, new HashSet<FolderDiff.Entry>(actual));
        Assert.assertEquals(expected.size(), diff.copyFilePairs.size());
    }

    private void assertMarkAsNewOrModified(Set<FolderDiff.Entry> expected, long lastBuildTime, File src, File dst)
            throws IOException {
        FolderDiffFake diff = getFolderDiff(src, dst);
        List<FolderDiff.Entry> actuaResult = diff.getNewOrModifiedFiles(lastBuildTime, false);
        assertMarkAsNewOrModified(expected, actuaResult, diff);
    }

    private void assertMarkAsDelete(Set<FolderDiff.Entry> expected, File src, File dst)
            throws InterruptedException, IOException {
        FolderDiffFake diff = getFolderDiff(src, dst);
        List<FolderDiff.Entry> result = diff.getFiles2Delete(false);
        assertEquals(expected, new HashSet<FolderDiff.Entry>(result));
        Assert.assertEquals(expected.size(), diff.deleteFiles.size());
    }

    private FolderDiffFake getFolderDiff(File src, File dst) {
        return new FolderDiffFake(src.getAbsolutePath(), dst.getAbsolutePath());
    }

}
