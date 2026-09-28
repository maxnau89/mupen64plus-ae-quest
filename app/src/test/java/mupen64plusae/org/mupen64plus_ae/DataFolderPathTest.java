package mupen64plusae.org.mupen64plus_ae;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import paulscode.android.mupen64plusae.persistent.DataFolderPath;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class DataFolderPathTest
{
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void emptyAndBlankInputIsRejected()
    {
        assertEquals(DataFolderPath.Status.EMPTY, DataFolderPath.check(null).status);
        assertEquals(DataFolderPath.Status.EMPTY, DataFolderPath.check("   ").status);
    }

    @Test
    public void existingWritableFolderIsAccepted() throws Exception
    {
        File dir = tmp.newFolder("M64Plus");
        DataFolderPath.Result r = DataFolderPath.check("  " + dir.getAbsolutePath() + " ");
        assertEquals(DataFolderPath.Status.OK, r.status);
        assertEquals(dir.getAbsoluteFile(), r.folder.getAbsoluteFile());
    }

    @Test
    public void missingLastSegmentIsCreated()
    {
        File dir = new File(tmp.getRoot(), "M64Plus");
        assertFalse(dir.exists());
        assertEquals(DataFolderPath.Status.OK, DataFolderPath.check(dir.getAbsolutePath()).status);
        assertTrue(dir.isDirectory());
    }

    @Test
    public void missingParentIsNotCreated()
    {
        File dir = new File(tmp.getRoot(), "typo/M64Plus");
        assertEquals(DataFolderPath.Status.NOT_FOUND, DataFolderPath.check(dir.getAbsolutePath()).status);
        assertFalse(dir.getParentFile().exists());
    }

    @Test
    public void fileIsNotAFolder() throws Exception
    {
        File file = tmp.newFile("rom.z64");
        assertEquals(DataFolderPath.Status.NOT_DIRECTORY, DataFolderPath.check(file.getAbsolutePath()).status);
    }

    @Test
    public void readOnlyFolderIsRejected() throws Exception
    {
        File dir = tmp.newFolder("readonly");
        assertTrue(dir.setWritable(false, false));
        try {
            // root ignores file permissions, so the check only means something as a normal user
            assumeFalse(dir.canWrite());
            assertEquals(DataFolderPath.Status.NOT_WRITABLE, DataFolderPath.check(dir.getAbsolutePath()).status);
        } finally {
            dir.setWritable(true, false);
        }
    }
}
