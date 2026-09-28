/*
 * Mupen64PlusAE, an N64 emulator for the Android platform
 *
 * This file is part of Mupen64PlusAE.
 *
 * Mupen64PlusAE is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Mupen64PlusAE is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Mupen64PlusAE. If
 * not, see <http://www.gnu.org/licenses/>.
 */
package paulscode.android.mupen64plusae.persistent;

import java.io.File;

/**
 * Checks a typed-in path for the game data folder. Unlike the ROM folder, the data folder has to
 * be writable, and a missing last path segment is created so a fresh folder like the default
 * /sdcard/M64Plus can be entered directly. Deeper missing trees are refused to keep typos from
 * scattering directories over the storage. Plain Java so it can be unit tested on the JVM.
 */
public final class DataFolderPath
{
    public enum Status { OK, EMPTY, NOT_FOUND, NOT_DIRECTORY, NOT_WRITABLE }

    public static final class Result
    {
        public final Status status;
        public final File folder;

        Result(Status status, File folder)
        {
            this.status = status;
            this.folder = folder;
        }
    }

    private DataFolderPath()
    {
    }

    public static Result check(String rawPath)
    {
        final String path = rawPath == null ? "" : rawPath.trim();
        if (path.isEmpty()) {
            return new Result(Status.EMPTY, null);
        }

        final File folder = new File(path);
        if (!folder.exists()) {
            final File parent = folder.getParentFile();
            if (parent == null || !parent.isDirectory() || !folder.mkdir()) {
                return new Result(Status.NOT_FOUND, folder);
            }
        }
        if (!folder.isDirectory()) {
            return new Result(Status.NOT_DIRECTORY, folder);
        }
        if (!folder.canWrite()) {
            return new Result(Status.NOT_WRITABLE, folder);
        }
        return new Result(Status.OK, folder);
    }
}
