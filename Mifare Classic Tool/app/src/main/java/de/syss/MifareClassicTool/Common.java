/*
 * Copyright 2013 Gerhard Klostermeier
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package de.syss.MifareClassicTool;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.MifareClassic;
import android.nfc.tech.NfcA;
import android.os.Build;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.OpenableColumns;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.SparseArray;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;

import de.syss.MifareClassicTool.Activities.IActivityThatReactsToSave;

import static de.syss.MifareClassicTool.Activities.Preferences.Preference.AutoCopyUID;
import static de.syss.MifareClassicTool.Activities.Preferences.Preference.UIDFormat;
import static de.syss.MifareClassicTool.Activities.Preferences.Preference.UseInternalStorage;

/**
 * Common functions and variables for all Activities.
 * @author Gerhard Klostermeier
 */
public class Common extends Application {

    /**
     * True if this is the donate version of MCT.
     */
    public static final boolean IS_DONATE_VERSION = false;
    /**
     * The directory name of the root directory of this app
     * (on external storage).
     */
    public static final String HOME_DIR = "/MifareClassicTool";

    /**
     * The directory name  of the key files directory.
     * (sub directory of {@link #HOME_DIR}.)
     */
    public static final String KEYS_DIR = "key-files";

    /**
     * The directory name  of the dump files directory.
     * (sub directory of {@link #HOME_DIR}.)
     */
    public static final String DUMPS_DIR = "dump-files";

    /**
     * The name of the directory where dump/key files get exported to.
     */
    public static final String EXPORT_DIR = "export";

    /**
     * The directory name of the folder where temporary files are
     * stored. The directory will be cleaned during the creation of
     * the main activity
     * ({@link de.syss.MifareClassicTool.Activities.MainMenu}).
     * (sub directory of {@link #HOME_DIR}.)
     */
    public static final String TMP_DIR = "tmp";

    /**
     * This file contains some standard MIFARE keys.
     * <ul>
     * <li>0xFFFFFFFFFFFF - Un-formatted, factory fresh tags.</li>
     * <li>0xA0A1A2A3A4A5 - First sector of the tag (MIFARE MAD).</li>
     * <li>0xD3F7D3F7D3F7 - NDEF formatted tags.</li>
     * </ul>
     */
    public static final String STD_KEYS = "std.keys";

    /**
     * Keys taken from SLURP by Anders Sundman anders@4zm.org
     * (and a short google search).
     * https://github.com/4ZM/slurp/blob/master/res/xml/mifare_default_keys.xml
     */
    public static final String STD_KEYS_EXTENDED = "extended-std.keys";

    /**
     * Possible operations the on a MIFARE Classic Tag.
     */
    public enum Operations {
        Read, Write, Increment, DecTransRest, ReadKeyA, ReadKeyB, ReadAC,
        WriteKeyA, WriteKeyB, WriteAC
    }

    private static final String LOG_TAG = Common.class.getSimpleName();

    /**
     * The last detected tag.
     * Set by {@link #treatAsNewTag(Intent, Context)}
     */
    private static Tag mTag = null;

    /**
     * The last detected UID.
     * Set by {@link #treatAsNewTag(Intent, Context)}
     */
    private static byte[] mUID = null;

    /**
     * Just a global storage to save key maps generated by
     * {@link de.syss.MifareClassicTool.Activities.KeyMapCreator}
     * @see de.syss.MifareClassicTool.Activities.KeyMapCreator
     * @see MCReader#getKeyMap()
     */
    private static SparseArray<byte[][]> mKeyMap = null;

    /**
     * Global storage for the point where
     * {@link de.syss.MifareClassicTool.Activities.KeyMapCreator} started to
     * create a key map.
     * @see de.syss.MifareClassicTool.Activities.KeyMapCreator
     * @see MCReader#getKeyMap()
     */
    private static int mKeyMapFrom = -1;

    /**
     * Global storage for the point where
     * {@link de.syss.MifareClassicTool.Activities.KeyMapCreator} ended to
     * create a key map.
     * @see de.syss.MifareClassicTool.Activities.KeyMapCreator
     * @see MCReader#getKeyMap()
     */
    private static int mKeyMapTo = -1;

    /**
     * The version code from the Android manifest.
     */
    private static String mVersionCode;

    /**
     * If NFC is disabled and the user chose to use MCT in editor only mode,
     * the choice is remembered here.
     */
    private static boolean mUseAsEditorOnly = false;

    /**
     * 1 if the device does support MIFARE Classic. -1 if it doesn't support
     * it. 0 if the support check was not yet performed.
     * Checking for MIFARE Classic support is really expensive. Therefore
     * remember the result here.
     */
    private static int mHasMifareClassicSupport = 0;

    /**
     * The component name of the activity that is in foreground and
     * should receive the new detected tag object by an external reader.
     */
    private static ComponentName mPendingComponentName = null;


    private static NfcAdapter mNfcAdapter;
    private static Context mAppContext;
    private static float mScale;

// ############################################################################

    /**
     * Initialize the {@link #mAppContext} with the application context
     * (for {@link #getPreferences()}) and {@link #mVersionCode}.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        mAppContext = getApplicationContext();
        mScale = getResources().getDisplayMetrics().density;

        try {
            mVersionCode = getPackageManager().getPackageInfo(
                    getPackageName(), 0).versionName;
        } catch (NameNotFoundException e) {
            Log.d(LOG_TAG, "Version not found.");
        }
    }

    /**
     * Check if the user granted read/write permissions to the external storage.
     * @param context The Context to check the permissions for.
     * @return True if granted the permissions. False otherwise.
     */
    public static boolean hasWritePermissionToExternalStorage(Context context) {
        return ContextCompat.checkSelfPermission(context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks if external storage is available for read and write.
     * If not, show an error Toast.
     * @param context The Context in which the Toast will be shown.
     * @return True if external storage is writable. False otherwise.
     */
    public static boolean isExternalStorageWritableErrorToast(
            Context context) {
        if (!isExternalStorageMounted()) {
            Toast.makeText(context, R.string.info_no_external_storage,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Checks if external storage is available for read and write.
     * @return True if external storage is writable. False otherwise.
     */
    public static boolean isExternalStorageMounted() {
        return Environment.MEDIA_MOUNTED.equals(
                Environment.getExternalStorageState());
    }

    /**
     * Create a File object with a path that consists of its storage
     * (internal/external according to its preference) and the relative
     * path.
     * @param relativePath The relative path that gets appended to the
     * internal or external storage path part.
     * @param forceExternal Return the external path regardless of the options.
     * @return A File object with the absolute path of the storage and the
     * relative component given by the parameter.
     */
    public static File getFileFromStorage(String relativePath,
                boolean forceExternal) {
        File file;
        boolean isUseInternalStorage = getPreferences().getBoolean(
                UseInternalStorage.toString(), false);
        if (!forceExternal && isUseInternalStorage) {
            // Use internal storage.
            file = new File(mAppContext.getFilesDir() + relativePath);
        } else {
            // Use external storage (default).
            file = new File(Environment.getExternalStorageDirectory() +
                    relativePath);
        }
        return file;
    }

    /**
     * Create a File object with a path that consists of its storage
     * (internal/external according to its preference) and the relative
     * path.
     * @param relativePath The relative path that gets appended to the
     * internal or external storage path part.
     * @return A File object with the absolute path of the storage and the
     * relative component given by the parameter.
     */
    public static File getFileFromStorage(String relativePath) {
        return getFileFromStorage(relativePath, false);
    }

    /**
     * Read a file line by line. The file should be a simple text file.
     * Empty lines will not be read.
     * @param file The file to read.
     * @param readComments Whether to read comments or to ignore them.
     * Comments are lines STARTING with "#" (and empty lines).
     * @param context  The context in which the possible "Out of memory"-Toast
     * will be shown.
     * @return Array of strings representing the lines of the file.
     * If the file is empty or an error occurs "null" will be returned.
     */
    public static String[] readFileLineByLine(File file, boolean readComments,
            Context context) {
        String[] ret = null;
        BufferedReader reader = null;
        if (file != null && isExternalStorageMounted() && file.exists()) {
            try {
                reader = new BufferedReader(new FileReader(file));
                ret = readLineByLine(reader, readComments, context);
            } catch (FileNotFoundException ex) {
                ret = null;
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "Error while closing file.", e);
                        ret = null;
                    }
                }
            }
        }
        return ret;
    }

    // TODO: doc.
    public static String[] readUriLineByLine(Uri uri, Context context){
        InputStream contentStream = null;
        String[] ret = null;
        try {
            contentStream = context.getContentResolver().openInputStream(uri);
        } catch (FileNotFoundException ex) {
            return null;
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(contentStream));
        ret = readLineByLine(reader, true, context);
        try {
            reader.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error while closing file.", e);
            return null;
        }
        return ret;
    }

    // TODO: doc.
    public static byte[] readUriRaw(Uri uri, Context context) {
        InputStream contentStream = null;
        String[] ret = null;
        try {
            contentStream = context.getContentResolver().openInputStream(uri);
        } catch (FileNotFoundException ex) {
            return null;
        }

        int len;
        byte[] data = new byte[16384];
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            while ((len = contentStream.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, len);
            }
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error while reading from file.", e);
            return null;
        }

        return buffer.toByteArray();
    }

    // TODO: doc.
    private static String[] readLineByLine(BufferedReader reader,
            boolean readComments, Context context) {
        String[] ret = null;
        String line;
        ArrayList<String> linesArray = new ArrayList<>();
        try {
            while ((line = reader.readLine()) != null) {
                // Ignore empty lines.
                // Ignore comments if readComments == false.
                if (!line.equals("")
                        && (readComments || !line.startsWith("#"))) {
                    try {
                        linesArray.add(line);
                    } catch (OutOfMemoryError e) {
                        // Error. File is too big
                        // (too many lines, out of memory).
                        Toast.makeText(context, R.string.info_file_to_big,
                                Toast.LENGTH_LONG).show();
                        return null;
                    }
                }
            }
        } catch (IOException ex) {
            Log.e(LOG_TAG, "Error while reading from file.", ex);
            ret = null;
        }
        if (linesArray.size() > 0) {
            ret = linesArray.toArray(new String[linesArray.size()]);
        } else {
            ret = new String[]{""};
        }
        return ret;
    }

    // TODO: doc. https://stackoverflow.com/a/25005243
    public static String getFileName(Uri uri, Context context) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            Cursor cursor = context.getContentResolver().query(
                    uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    result = cursor.getString(cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME));
                }
            } finally {
                cursor.close();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        return result;
    }

    /**
     * Check if the file already exists. If so, present a dialog to the user
     * with the options: "Replace", "Append" and "Cancel".
     * @param file File that will be written.
     * @param lines The lines to save.
     * @param isDump Set to True if file and lines are a dump file.
     * @param context The Context in which the dialog and Toast will be shown.
     * @param activity An object (most likely an Activity) that implements the
     * onSaveSuccessful() and onSaveFailure() methods. These methods will
     * be called according to the save process. Also, onSaveFailure() will
     * be called if the user hints cancel.
     * @see #saveFile(File, String[], boolean)
     * @see #saveFileAppend(File, String[], boolean)
     */
    public static void checkFileExistenceAndSave(final File file,
            final String[] lines, final boolean isDump, final Context context,
            final IActivityThatReactsToSave activity) {
        if (file.exists()) {
            // Save conflict for dump file or key file?
            int message = R.string.dialog_save_conflict_keyfile;
            if (isDump) {
                message = R.string.dialog_save_conflict_dump;
            }

            // File already exists. Replace? Append? Cancel?
            new AlertDialog.Builder(context)
            .setTitle(R.string.dialog_save_conflict_title)
            .setMessage(message)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(R.string.action_replace,
                    (dialog, which) -> {
                        // Replace.
                        if (Common.saveFile(file, lines, false)) {
                            Toast.makeText(context, R.string.info_save_successful,
                                    Toast.LENGTH_LONG).show();
                            activity.onSaveSuccessful();
                        } else {
                            Toast.makeText(context, R.string.info_save_error,
                                    Toast.LENGTH_LONG).show();
                            activity.onSaveFailure();
                        }
                    })
            .setNeutralButton(R.string.action_append,
                    (dialog, which) -> {
                        // Append.
                        if (Common.saveFileAppend(file, lines, isDump)) {
                            Toast.makeText(context, R.string.info_save_successful,
                                    Toast.LENGTH_LONG).show();
                            activity.onSaveSuccessful();
                        } else {
                            Toast.makeText(context, R.string.info_save_error,
                                    Toast.LENGTH_LONG).show();
                            activity.onSaveFailure();
                        }
                    })
            .setNegativeButton(R.string.action_cancel,
                    (dialog, id) -> {
                        // Cancel.
                        activity.onSaveFailure();
                    }).show();
        } else {
            if (Common.saveFile(file, lines, false)) {
                Toast.makeText(context, R.string.info_save_successful,
                        Toast.LENGTH_LONG).show();
                activity.onSaveSuccessful();
            } else {
                Toast.makeText(context, R.string.info_save_error,
                        Toast.LENGTH_LONG).show();
                activity.onSaveFailure();
            }
        }
    }

    /**
     * Append an array of strings (each field is one line) to a given file.
     * @param file The file to write to.
     * @param lines The lines to save.
     * @param comment If true, add a comment before the appended section.
     * @return True if file writing was successful. False otherwise.
     */
    public static boolean saveFileAppend(File file, String[] lines,
            boolean comment) {
        if (comment) {
            // Append to a existing file.
            String[] newLines = new String[lines.length + 4];
            System.arraycopy(lines, 0, newLines, 4, lines.length);
            newLines[1] = "";
            newLines[2] = "# Append #######################";
            newLines[3] = "";
            lines = newLines;
        }
        return saveFile(file, lines, true);
    }

    /**
     * Write an array of strings (each field is one line) to a given file.
     * @param file The file to write to.
     * @param lines The lines to save.
     * @param append Append to file (instead of replacing its content).
     * @return True if file writing was successful. False otherwise.
     */
    public static boolean saveFile(File file, String[] lines, boolean append) {
        boolean noError = true;
        if (file != null && lines != null && isExternalStorageMounted()) {
            BufferedWriter bw = null;
            try {
                bw = new BufferedWriter(new FileWriter(file, append));
                // Add new line before appending.
                if (append) {
                    bw.newLine();
                }
                int i;
                for(i = 0; i < lines.length-1; i++) {
                    bw.write(lines[i]);
                    bw.newLine();
                }
                bw.write(lines[i]);
            } catch (IOException | NullPointerException ex) {
                Log.e(LOG_TAG, "Error while writing to '"
                        + file.getName() + "' file.", ex);
                noError = false;

            } finally {
                if (bw != null) {
                    try {
                        bw.close();
                    } catch (IOException e) {
                        Log.e(LOG_TAG, "Error while closing file.", e);
                        noError = false;
                    }
                }
            }
        } else {
            noError = false;
        }
        return noError;
    }

    // TODO: doc.
    public static boolean saveFile(File file, byte[] bytes, boolean append) {
        boolean noError = true;
        if (file != null && bytes != null && isExternalStorageMounted()) {
            try {
                FileOutputStream stream = new FileOutputStream(file, append);
                stream.write(bytes);
            } catch ( IOException | NullPointerException e) {
                Log.e(LOG_TAG, "Error while writing to '"
                        + file.getName() + "' file.", e);
                return false;
            }
        } else {
            return false;
        }
        return true;
    }

    /**
     * Get the shared preferences with application context for saving
     * and loading ("global") values.
     * @return The shared preferences object with application context.
     */
    public static SharedPreferences getPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(mAppContext);
    }

    /**
     * Enables the NFC foreground dispatch system for the given Activity.
     * @param targetActivity The Activity that is in foreground and wants to
     * have NFC Intents.
     * @see #disableNfcForegroundDispatch(Activity)
     */
    public static void enableNfcForegroundDispatch(Activity targetActivity) {
        if (mNfcAdapter != null && mNfcAdapter.isEnabled()) {

            Intent intent = new Intent(targetActivity,
                    targetActivity.getClass()).addFlags(
                            Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    targetActivity, 0, intent, 0);
            mNfcAdapter.enableForegroundDispatch(
                    targetActivity, pendingIntent, null, new String[][] {
                            new String[] { NfcA.class.getName() } });
        }
    }

    /**
     * Disable the NFC foreground dispatch system for the given Activity.
     * @param targetActivity An Activity that is in foreground and has
     * NFC foreground dispatch system enabled.
     * @see #enableNfcForegroundDispatch(Activity)
     */
    public static void disableNfcForegroundDispatch(Activity targetActivity) {
        if (mNfcAdapter != null && mNfcAdapter.isEnabled()) {
            mNfcAdapter.disableForegroundDispatch(targetActivity);
        }
    }

    /**
     * For Activities which want to treat new Intents as Intents with a new
     * Tag attached. If the given Intent has a Tag extra, it will be patched
     * by {@link MCReader#patchTag(Tag)} and  {@link #mTag} as well as
     * {@link #mUID} will be updated. A Toast message will be shown in the
     * Context of the calling Activity. This method will also check if the
     * device/tag supports MIFARE Classic (see return values and
     * {@link #checkMifareClassicSupport(Tag, Context)}).
     * @param intent The Intent which should be checked for a new Tag.
     * @param context The Context in which the Toast will be shown.
     * @return
     * <ul>
     * <li>0 - The device/tag supports MIFARE Classic</li>
     * <li>-1 - Device does not support MIFARE Classic.</li>
     * <li>-2 - Tag does not support MIFARE Classic.</li>
     * <li>-3 - Error (tag or context is null).</li>
     * <li>-4 - Wrong Intent (action is not "ACTION_TECH_DISCOVERED").</li>
     * </ul>
     * @see #mTag
     * @see #mUID
     * @see #checkMifareClassicSupport(Tag, Context)
     */
    public static int treatAsNewTag(Intent intent, Context context) {
        // Check if Intent has a NFC Tag.
        if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(intent.getAction())) {
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            tag = MCReader.patchTag(tag);
            setTag(tag);

            boolean isCopyUID = getPreferences().getBoolean(
                    AutoCopyUID.toString(), false);
            if (isCopyUID) {
                int format = getPreferences().getInt(
                        UIDFormat.toString(), 0);
                String fmtUID = byte2FmtString(tag.getId(),format);
                // Show Toast with copy message.
                Toast.makeText(context,
                        "UID " + context.getResources().getString(
                                R.string.info_copied_to_clipboard)
                                .toLowerCase() + " (" + fmtUID + ")",
                        Toast.LENGTH_SHORT).show();
                copyToClipboard(fmtUID, context, false);
            } else {
                // Show Toast message with UID.
                String id = context.getResources().getString(
                        R.string.info_new_tag_found) + " (UID: ";
                id += byte2HexString(tag.getId());
                id += ")";
                Toast.makeText(context, id, Toast.LENGTH_LONG).show();
            }
            return checkMifareClassicSupport(tag, context);
        }
        return -4;
    }

    /**
     * Check if the device supports the MIFARE Classic technology.
     * In order to do so, there is a first check ensure the device actually has
     * a NFC hardware (if not, {@link #mUseAsEditorOnly} is set to true).
     * After this, this function will check if there are files
     * like "/dev/bcm2079x-i2c" or "/system/lib/libnfc-bcrm*". Files like
     * these are indicators for a NFC controller manufactured by Broadcom.
     * Broadcom chips don't support MIFARE Classic.
     * @return True if the device supports MIFARE Classic. False otherwise.
     * @see #mHasMifareClassicSupport
     * @see #mUseAsEditorOnly
     */
    public static boolean hasMifareClassicSupport() {
        if (mHasMifareClassicSupport != 0) {
            return mHasMifareClassicSupport == 1;
        }

        // Check for the MifareClassic class.
        // It is most likely there on all NFC enabled phones.
        // Therefore this check is not needed.
        /*
        try {
            Class.forName("android.nfc.tech.MifareClassic");
        } catch( ClassNotFoundException e ) {
            // Class not found. Devices does not support MIFARE Classic.
            return false;
        }
        */

        // Check if ther is any NFC hardware at all.
        if (NfcAdapter.getDefaultAdapter(mAppContext) == null) {
            mUseAsEditorOnly = true;
            mHasMifareClassicSupport = -1;
            return false;
        }

        // Check if there is the NFC device "bcm2079x-i2c".
        // Chips by Broadcom don't support MIFARE Classic.
        // This could fail because on a lot of devices apps don't have
        // the sufficient permissions.
        // Another exception:
        // The Lenovo P2 has a device at "/dev/bcm2079x-i2c" but is still
        // able of reading/writing MIFARE Classic tags. I don't know why...
        // https://github.com/ikarus23/MifareClassicTool/issues/152
        boolean isLenovoP2 = Build.MANUFACTURER.equals("LENOVO")
                && Build.MODEL.equals("Lenovo P2a42");
        File device = new File("/dev/bcm2079x-i2c");
        if (!isLenovoP2 && device.exists()) {
            mHasMifareClassicSupport = -1;
            return false;
        }

        // Check if there is the NFC device "pn544".
        // The PN544 NFC chip is manufactured by NXP.
        // Chips by NXP support MIFARE Classic.
        device = new File("/dev/pn544");
        if (device.exists()) {
            mHasMifareClassicSupport = 1;
            return true;
        }

        // Check if there are NFC libs with "brcm" in their names.
        // "brcm" libs are for devices with Broadcom chips. Broadcom chips
        // don't support MIFARE Classic.
        File libsFolder = new File("/system/lib");
        File[] libs = libsFolder.listFiles();
        for (File lib : libs) {
            if (lib.isFile()
                    && lib.getName().startsWith("libnfc")
                    && lib.getName().contains("brcm")
                    // Add here other non NXP NFC libraries.
                    ) {
                mHasMifareClassicSupport = -1;
                return false;
            }
        }

        mHasMifareClassicSupport = 1;
        return true;
    }

    /**
     * Check if the tag and the device support the MIFARE Classic technology.
     * @param tag The tag to check.
     * @param context The context of the package manager.
     * @return
     * <ul>
     * <li>0 - Device and tag support MIFARE Classic.</li>
     * <li>-1 - Device does not support MIFARE Classic.</li>
     * <li>-2 - Tag does not support MIFARE Classic.</li>
     * <li>-3 - Error (tag or context is null).</li>
     * </ul>
     */
    public static int checkMifareClassicSupport(Tag tag, Context context) {
        if (tag == null || context == null) {
            // Error.
            return -3;
        }

        if (Arrays.asList(tag.getTechList()).contains(
                MifareClassic.class.getName())) {
            // Device and tag support MIFARE Classic.
            return 0;

        // This is no longer valid. There are some devices (e.g. LG's F60)
        // that have this system feature but no MIFARE Classic support.
        // (The F60 has a Broadcom NFC controller.)
        /*
        } else if (context.getPackageManager().hasSystemFeature(
                "com.nxp.mifare")){
            // Tag does not support MIFARE Classic.
            return -2;
        */

        } else {
            // Check if device does not support MIFARE Classic.
            // For doing so, check if the SAK of the tag indicate that
            // it's a MIFARE Classic tag.
            // See: https://www.nxp.com/docs/en/application-note/AN10834.pdf
            NfcA nfca = NfcA.get(tag);
            byte sak = (byte)nfca.getSak();
            if ((sak>>1 & 1) == 1) {
                // RFU.
                return -2;
            } else {
                if ((sak>>3 & 1) == 1) { // SAK bit 4 = 1?
                    if((sak>>4 & 1) == 1) { // SAK bit 5 = 1?
                        // MIFARE Classic 4k
                        // MIFARE SmartMX 4K
                        // MIFARE PlusS 4K SL1
                        // MIFARE PlusX 4K SL1
                        return -1;
                    } else {
                        if ((sak & 1) == 1) { // SAK bit 1 = 1?
                            // MIFARE Mini
                            return -1;
                        } else {
                            // MIFARE Classic 1k
                            // MIFARE SmartMX 1k
                            // MIFARE PlusS 2K SL1
                            // MIFARE PlusX 2K SL2
                            return -1;
                        }
                    }
                } else {
                    // Some MIFARE tag, but not Classic or Classic compatible.
                    return -2;
                }
            }

            // Old MIFARE Classic support check. No longer valid.
            // Check if the ATQA + SAK of the tag indicate that it's a MIFARE Classic tag.
            // See: http://www.nxp.com/documents/application_note/AN10833.pdf
            // (Table 5 and 6)
            // 0x28 is for some emulated tags.
            /*
            NfcA nfca = NfcA.get(tag);
            byte[] atqa = nfca.getAtqa();
            if (atqa[1] == 0 &&
                    (atqa[0] == 4 || atqa[0] == (byte)0x44 ||
                     atqa[0] == 2 || atqa[0] == (byte)0x42)) {
                // ATQA says it is most likely a MIFARE Classic tag.
                byte sak = (byte)nfca.getSak();
                if (sak == 8 || sak == 9 || sak == (byte)0x18 ||
                                            sak == (byte)0x88 ||
                                            sak == (byte)0x28) {
                    // SAK says it is most likely a MIFARE Classic tag.
                    // --> Device does not support MIFARE Classic.
                    return -1;
                }
            }
            // Nope, it's not the device (most likely).
            // The tag does not support MIFARE Classic.
            return -2;
            */
        }
    }

    /**
     * Open another app.
     * @param context current Context, like Activity, App, or Service
     * @param packageName the full package name of the app to open
     * @return true if likely successful, false if unsuccessful
     */
    public static boolean openApp(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            Intent i = manager.getLaunchIntentForPackage(packageName);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            context.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Check whether the service of the "External NFC" app is running or not.
     * This will only work for Android < 8.
     * @param context The context for the system service.
     * @return
     * <ul>
     * <li>0 - Service is not running.</li>
     * <li>1 - Service is running.</li>
     * <li>-1 - Can not check because Android version is >= 8.</li>
     * </ul>
     */
    public static int isExternalNfcServiceRunning(Context context) {
        // getRunningServices() is deprecated since Android 8.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(
                            Context.ACTIVITY_SERVICE);
            for (ActivityManager.RunningServiceInfo service
                    : manager.getRunningServices(Integer.MAX_VALUE)) {
                if ("eu.dedb.nfc.service.NfcService".equals(
                        service.service.getClassName())) {
                    return 1;
                }
            }
            return 0;
        }
        return -1;
    }

    /**
     * Find out whether the "External NFC" app is installed or not.
     * @param context The context for the package manager.
     * @return True if "External NFC" is installed. False otherwise.
     */
    public static boolean hasExternalNfcInstalled(Context context) {
        return Common.isAppInstalled("eu.dedb.nfc.service", context);
    }

    /**
     * Check whether an app is installed or not.
     * @param uri The URI (package name) of the app.
     * @param context The context for the package manager.
     * @return True if the app is installed. False otherwise.
     */
    public static boolean isAppInstalled(String uri, Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(uri, PackageManager.GET_ACTIVITIES);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Create a connected {@link MCReader} if there is a present MIFARE Classic
     * tag. If there is no MIFARE Classic tag an error
     * message will be displayed to the user.
     * @param context The Context in which the error Toast will be shown.
     * @return A connected {@link MCReader} or "null" if no tag was present.
     */
    public static MCReader checkForTagAndCreateReader(Context context) {
        MCReader reader;
        boolean tagLost = false;
        // Check for tag.
        if (mTag != null && (reader = MCReader.get(mTag)) != null) {
            try {
                reader.connect();
            } catch (Exception e) {
                tagLost = true;
            }
            if (!tagLost && !reader.isConnected()) {
                reader.close();
                tagLost = true;
            }
            if (!tagLost) {
                return reader;
            }
        }

        // Error. The tag is gone.
        Toast.makeText(context, R.string.info_no_tag_found,
                Toast.LENGTH_LONG).show();
        return null;
    }

    /**
     * Depending on the provided Access Conditions this method will return
     * with which key you can achieve the operation ({@link Operations})
     * you asked for.<br />
     * This method contains the table from the NXP MIFARE Classic Datasheet.
     * @param c1 Access Condition byte "C!".
     * @param c2 Access Condition byte "C2".
     * @param c3 Access Condition byte "C3".
     * @param op The operation you want to do.
     * @param isSectorTrailer True if it is a Sector Trailer, False otherwise.
     * @param isKeyBReadable True if key B is readable, False otherwise.
     * @return The operation "op" is possible with:<br />
     * <ul>
     * <li>0 - Never.</li>
     * <li>1 - Key A.</li>
     * <li>2 - Key B.</li>
     * <li>3 - Key A or B.</li>
     * <li>-1 - Error.</li>
     * </ul>
     */
    public static int getOperationInfoForBlock(byte c1, byte c2, byte c3,
            Operations op, boolean isSectorTrailer, boolean isKeyBReadable) {
        // Is Sector Trailer?
        if (isSectorTrailer) {
            // Sector Trailer.
            if (op != Operations.ReadKeyA && op != Operations.ReadKeyB
                    && op != Operations.ReadAC
                    && op != Operations.WriteKeyA
                    && op != Operations.WriteKeyB
                    && op != Operations.WriteAC) {
                // Error. Sector Trailer but no Sector Trailer permissions.
                return 4;
            }
            if          (c1 == 0 && c2 == 0 && c3 == 0) {
                if (op == Operations.WriteKeyA
                        || op == Operations.WriteKeyB
                        || op == Operations.ReadKeyB
                        || op == Operations.ReadAC) {
                    return 1;
                }
                return 0;
            } else if   (c1 == 0 && c2 == 1 && c3 == 0) {
                if (op == Operations.ReadKeyB
                        || op == Operations.ReadAC) {
                    return 1;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 0 && c3 == 0) {
                if (op == Operations.WriteKeyA
                        || op == Operations.WriteKeyB) {
                    return 2;
                }
                if (op == Operations.ReadAC) {
                    return 3;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 1 && c3 == 0) {
                if (op == Operations.ReadAC) {
                    return 3;
                }
                return 0;
            } else if   (c1 == 0 && c2 == 0 && c3 == 1) {
                if (op == Operations.ReadKeyA) {
                    return 0;
                }
                return 1;
            } else if   (c1 == 0 && c2 == 1 && c3 == 1) {
                if (op == Operations.ReadAC) {
                    return 3;
                }
                if (op == Operations.ReadKeyA
                        || op == Operations.ReadKeyB) {
                    return 0;
                }
                return 2;
            } else if   (c1 == 1 && c2 == 0 && c3 == 1) {
                if (op == Operations.ReadAC) {
                    return 3;
                }
                if (op == Operations.WriteAC) {
                    return 2;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 1 && c3 == 1) {
                if (op == Operations.ReadAC) {
                    return 3;
                }
                return 0;
            } else {
                return -1;
            }
        } else {
            // Data Block.
            if (op != Operations.Read && op != Operations.Write
                    && op != Operations.Increment
                    && op != Operations.DecTransRest) {
                // Error. Data block but no data block permissions.
                return -1;
            }
            if          (c1 == 0 && c2 == 0 && c3 == 0) {
                return (isKeyBReadable) ? 1 : 3;
            } else if   (c1 == 0 && c2 == 1 && c3 == 0) {
                if (op == Operations.Read) {
                    return (isKeyBReadable) ? 1 : 3;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 0 && c3 == 0) {
                if (op == Operations.Read) {
                    return (isKeyBReadable) ? 1 : 3;
                }
                if (op == Operations.Write) {
                    return 2;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 1 && c3 == 0) {
                if (op == Operations.Read
                        || op == Operations.DecTransRest) {
                    return (isKeyBReadable) ? 1 : 3;
                }
                return 2;
            } else if   (c1 == 0 && c2 == 0 && c3 == 1) {
                if (op == Operations.Read
                        || op == Operations.DecTransRest) {
                    return (isKeyBReadable) ? 1 : 3;
                }
                return 0;
            } else if   (c1 == 0 && c2 == 1 && c3 == 1) {
                if (op == Operations.Read || op == Operations.Write) {
                    return 2;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 0 && c3 == 1) {
                if (op == Operations.Read) {
                    return 2;
                }
                return 0;
            } else if   (c1 == 1 && c2 == 1 && c3 == 1) {
                return 0;
            } else {
                // Error.
                return -1;
            }
        }
    }

    /**
     * Check if key B is readable.
     * Key B is readable for the following configurations:
     * <ul>
     * <li>C1 = 0, C2 = 0, C3 = 0</li>
     * <li>C1 = 0, C2 = 0, C3 = 1</li>
     * <li>C1 = 0, C2 = 1, C3 = 0</li>
     * </ul>
     * @param c1 Access Condition byte "C1"
     * @param c2 Access Condition byte "C2"
     * @param c3 Access Condition byte "C3"
     * @return True if key B is readable. False otherwise.
     */
    public static boolean isKeyBReadable(byte c1, byte c2, byte c3) {
        return c1 == 0
                && ((c2 == 0 && c3 == 0)
                || (c2 == 1 && c3 == 0)
                || (c2 == 0 && c3 == 1));
    }

    /**
     * Convert the Access Condition bytes to a matrix containing the
     * resolved C1, C2 and C3 for each block.
     * @param acBytes The Access Condition bytes (3 byte).
     * @return Matrix of access conditions bits (C1-C3) where the first
     * dimension is the "C" parameter (C1-C3, Index 0-2) and the second
     * dimension is the block number (Index 0-3). If the ACs are incorrect
     * null will be returned.
     */
    public static byte[][] acBytesToACMatrix(byte acBytes[]) {
        // ACs correct?
        // C1 (Byte 7, 4-7) == ~C1 (Byte 6, 0-3) and
        // C2 (Byte 8, 0-3) == ~C2 (Byte 6, 4-7) and
        // C3 (Byte 8, 4-7) == ~C3 (Byte 7, 0-3)
        byte[][] acMatrix = new byte[3][4];
        if (acBytes.length > 2 &&
                (byte)((acBytes[1]>>>4)&0x0F)  ==
                        (byte)((acBytes[0]^0xFF)&0x0F) &&
                (byte)(acBytes[2]&0x0F) ==
                        (byte)(((acBytes[0]^0xFF)>>>4)&0x0F) &&
                (byte)((acBytes[2]>>>4)&0x0F)  ==
                        (byte)((acBytes[1]^0xFF)&0x0F)) {
            // C1, Block 0-3
            for (int i = 0; i < 4; i++) {
                acMatrix[0][i] = (byte)((acBytes[1]>>>4+i)&0x01);
            }
            // C2, Block 0-3
            for (int i = 0; i < 4; i++) {
                acMatrix[1][i] = (byte)((acBytes[2]>>>i)&0x01);
            }
            // C3, Block 0-3
            for (int i = 0; i < 4; i++) {
                acMatrix[2][i] = (byte)((acBytes[2]>>>4+i)&0x01);
            }
            return acMatrix;
        }
        return null;
    }

    /**
     * Convert a matrix with Access Conditions bits into normal 3
     * Access Condition bytes.
     * @param acMatrix Matrix of access conditions bits (C1-C3) where the first
     * dimension is the "C" parameter (C1-C3, Index 0-2) and the second
     * dimension is the block number (Index 0-3).
     * @return The Access Condition bytes (3 byte).
     */
    public static byte[] acMatrixToACBytes(byte acMatrix[][]) {
        if (acMatrix != null && acMatrix.length == 3) {
            for (int i = 0; i < 3; i++) {
                if (acMatrix[i].length != 4)
                    // Error.
                    return null;
            }
        } else {
            // Error.
            return null;
        }
        byte[] acBytes = new byte[3];
        // Byte 6, Bit 0-3.
        acBytes[0] = (byte)((acMatrix[0][0]^0xFF)&0x01);
        acBytes[0] |= (byte)(((acMatrix[0][1]^0xFF)<<1)&0x02);
        acBytes[0] |= (byte)(((acMatrix[0][2]^0xFF)<<2)&0x04);
        acBytes[0] |= (byte)(((acMatrix[0][3]^0xFF)<<3)&0x08);
        // Byte 6, Bit 4-7.
        acBytes[0] |= (byte)(((acMatrix[1][0]^0xFF)<<4)&0x10);
        acBytes[0] |= (byte)(((acMatrix[1][1]^0xFF)<<5)&0x20);
        acBytes[0] |= (byte)(((acMatrix[1][2]^0xFF)<<6)&0x40);
        acBytes[0] |= (byte)(((acMatrix[1][3]^0xFF)<<7)&0x80);
        // Byte 7, Bit 0-3.
        acBytes[1] = (byte)((acMatrix[2][0]^0xFF)&0x01);
        acBytes[1] |= (byte)(((acMatrix[2][1]^0xFF)<<1)&0x02);
        acBytes[1] |= (byte)(((acMatrix[2][2]^0xFF)<<2)&0x04);
        acBytes[1] |= (byte)(((acMatrix[2][3]^0xFF)<<3)&0x08);
        // Byte 7, Bit 4-7.
        acBytes[1] |= (byte)((acMatrix[0][0]<<4)&0x10);
        acBytes[1] |= (byte)((acMatrix[0][1]<<5)&0x20);
        acBytes[1] |= (byte)((acMatrix[0][2]<<6)&0x40);
        acBytes[1] |= (byte)((acMatrix[0][3]<<7)&0x80);
        // Byte 8, Bit 0-3.
        acBytes[2] = (byte)(acMatrix[1][0]&0x01);
        acBytes[2] |= (byte)((acMatrix[1][1]<<1)&0x02);
        acBytes[2] |= (byte)((acMatrix[1][2]<<2)&0x04);
        acBytes[2] |= (byte)((acMatrix[1][3]<<3)&0x08);
        // Byte 8, Bit 4-7.
        acBytes[2] |= (byte)((acMatrix[2][0]<<4)&0x10);
        acBytes[2] |= (byte)((acMatrix[2][1]<<5)&0x20);
        acBytes[2] |= (byte)((acMatrix[2][2]<<6)&0x40);
        acBytes[2] |= (byte)((acMatrix[2][3]<<7)&0x80);

        return acBytes;
    }

    /**
     * Check if a (hex) string is pure hex (0-9, A-F, a-f) and 16 byte
     * (32 chars) long. If not show an error Toast in the context.
     * @param hexString The string to check.
     * @param context The Context in which the Toast will be shown.
     * @return True if sting is hex an 16 Bytes long, False otherwise.
     */
    public static boolean isHexAnd16Byte(String hexString, Context context) {
        if (!hexString.matches("[0-9A-Fa-f]+")) {
            // Error, not hex.
            Toast.makeText(context, R.string.info_not_hex_data,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        if (hexString.length() != 32) {
            // Error, not 16 byte (32 chars).
            Toast.makeText(context, R.string.info_not_16_byte,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    /**
     * Check if the given block (hex string) is a value block.
     * NXP has PDFs describing what value blocks are. Google something
     * like "nxp MIFARE classic value block" if you want to have a
     * closer look.
     * @param hexString Block data as hex string.
     * @return True if it is a value block. False otherwise.
     */
    public static boolean isValueBlock(String hexString) {
        byte[] b = Common.hexStringToByteArray(hexString);
        if (b.length == 16) {
            // Google some NXP info PDFs about MIFARE Classic to see how
            // Value Blocks are formatted.
            // For better reading (~ = invert operator):
            // if (b0=b8 and b0=~b4) and (b1=b9 and b9=~b5) ...
            // ... and (b12=b14 and b13=b15 and b12=~b13) then
            if (    (b[0] == b[8] && (byte)(b[0]^0xFF) == b[4]) &&
                    (b[1] == b[9] && (byte)(b[1]^0xFF) == b[5]) &&
                    (b[2] == b[10] && (byte)(b[2]^0xFF) == b[6]) &&
                    (b[3] == b[11] && (byte)(b[3]^0xFF) == b[7]) &&
                    (b[12] == b[14] && b[13] == b[15] &&
                    (byte)(b[12]^0xFF) == b[13])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if all blocks (lines) contain valid data.
     * @param lines Blocks (incl. their sector header, e.g. "+Sector: 1").
     * @param ignoreAsterisk Ignore lines starting with "*" and move on
     * to the next sector (header).
     * @return <ul>
     * <li>0 - Everything is (most likely) O.K.</li>
     * <li>1 - Found a sector that has not 4 or 16 blocks.</li>
     * <li>2 - Found a block that has invalid characters (not hex or "-" as
     * marker for no key/no data).</li>
     * <li>3 - Found a block that has not 16 bytes (32 chars).</li>
     * <li>4 - A sector index is out of range.</li>
     * <li>5 - Found two times the same sector number (index).
     * Maybe this is a file containing multiple dumps
     * (the dump editor->save->append function was used)</li>
     * <li>6 - There are no lines (lines == null or len(lines) == 0).</li>
     * </ul>
     */
    public static int isValidDump(String[] lines, boolean ignoreAsterisk) {
        ArrayList<Integer> knownSectors = new ArrayList<>();
        int blocksSinceLastSectorHeader = 4;
        boolean is16BlockSector = false;
        if (lines == null || lines.length == 0) {
            // There are no lines.
            return 6;
        }
        for(String line : lines) {
            if ((!is16BlockSector && blocksSinceLastSectorHeader == 4)
                    || (is16BlockSector && blocksSinceLastSectorHeader == 16)) {
                // A sector header is expected.
                if (!line.matches("^\\+Sector: [0-9]{1,2}$")) {
                    // Not a valid sector length or not a valid sector header.
                    return 1;
                }
                int sector;
                try {
                    sector = Integer.parseInt(line.split(": ")[1]);
                } catch (Exception ex) {
                    // Not a valid sector header.
                    // Should not occur due to the previous check (regex).
                    return 1;
                }
                if (sector < 0 || sector > 39) {
                    // Sector out of range.
                    return 4;
                }
                if (knownSectors.contains(sector)) {
                    // Two times the same sector number (index).
                    // Maybe this is a file containing multiple dumps
                    // (the dump editor->save->append function was used).
                    return 5;
                }
                knownSectors.add(sector);
                is16BlockSector = (sector >= 32);
                blocksSinceLastSectorHeader = 0;
                continue;
            }
            if (line.startsWith("*") && ignoreAsterisk) {
                // Ignore line and move to the next sector.
                // (The line was a "No keys found or dead sector" message.)
                is16BlockSector = false;
                blocksSinceLastSectorHeader = 4;
                continue;
            }
            if (!line.matches("[0-9A-Fa-f-]+")) {
                // Not pure hex (or NO_DATA).
                return 2;
            }
            if (line.length() != 32) {
                // Not 32 chars per line.
                return 3;
            }
            blocksSinceLastSectorHeader++;
        }
        return 0;
    }

    /**
     * Show a Toast message with error information according to
     * {@link #isValidDump(String[], boolean)}.
     * @see #isValidDump(String[], boolean)
     */
    public static void isValidDumpErrorToast(int errorCode,
            Context context) {
        switch (errorCode) {
        case 1:
            Toast.makeText(context, R.string.info_valid_dump_not_4_or_16_lines,
                    Toast.LENGTH_LONG).show();
            break;
        case 2:
            Toast.makeText(context, R.string.info_valid_dump_not_hex,
                    Toast.LENGTH_LONG).show();
            break;
        case 3:
            Toast.makeText(context, R.string.info_valid_dump_not_16_bytes,
                    Toast.LENGTH_LONG).show();
            break;
        case 4:
            Toast.makeText(context, R.string.info_valid_dump_sector_range,
                    Toast.LENGTH_LONG).show();
            break;
        case 5:
            Toast.makeText(context, R.string.info_valid_dump_double_sector,
                    Toast.LENGTH_LONG).show();
            break;
        case 6:
            Toast.makeText(context, R.string.info_valid_dump_empty_dump,
                    Toast.LENGTH_LONG).show();
            break;
        }
    }

    /**
     * Reverse a byte Array (e.g. Little Endian -> Big Endian).
     * Hmpf! Java has no Array.reverse(). And I don't want to use
     * Commons.Lang (ArrayUtils) from Apache....
     * @param array The array to reverse (in-place).
     */
    public static void reverseByteArrayInPlace(byte[] array) {
        for(int i = 0; i < array.length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
    }


    /**
     * Convert byte array to a string of the specified format.
     * Format value corresponds to the pref radio button sequence.
     * @param bytes Bytes to convert.
     * @param fmt Format (0=Hex; 1=DecBE; 2=DecLE).
     * @return The bytes in the specified format.
     */
    public static String byte2FmtString(byte[] bytes, int fmt) {
        switch(fmt) {
            case 2:
                byte[] revBytes = bytes.clone();
                reverseByteArrayInPlace(revBytes);
                return hex2Dec(byte2HexString(revBytes));
            case 1:
                return hex2Dec(byte2HexString(bytes));
        }
        return byte2HexString(bytes);
    }

    /**
     * Convert a hexadecimal string to a decimal string.
     * Uses BigInteger only if the hexadecimal string is longer than 7 bytes.
     * @param hexString The hexadecimal value to convert.
     * @return String representation of the decimal value of hexString.
     */
    public static String hex2Dec(String hexString) {
        String ret;
        if (hexString == null || hexString.isEmpty()) {
            ret = "0";
        } else if (hexString.length() <= 14) {
            ret = Long.toString(Long.parseLong(hexString, 16));
        } else {
            BigInteger bigInteger = new BigInteger(hexString , 16);
            ret = bigInteger.toString();
        }
        return ret;
    }

    /**
     * Convert an array of bytes into a string of hex values.
     * @param bytes Bytes to convert.
     * @return The bytes in hex string format.
     */
    public static String byte2HexString(byte[] bytes) {
        StringBuilder ret = new StringBuilder();
        if (bytes != null) {
            for (Byte b : bytes) {
                ret.append(String.format("%02X", b.intValue() & 0xFF));
            }
        }
        return ret.toString();
    }

    /**
     * Convert a string of hex data into a byte array.
     * Original author is: Dave L. (http://stackoverflow.com/a/140861).
     * @param s The hex string to convert
     * @return An array of bytes with the values of the string.
     */
    public static byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        try {
            for (int i = 0; i < len; i += 2) {
                data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                                     + Character.digit(s.charAt(i+1), 16));
            }
        } catch (Exception e) {
            Log.d(LOG_TAG, "Argument(s) for hexStringToByteArray(String s)"
                    + "was not a hex string");
        }
        return data;
    }

    /**
     * Create a colored string.
     * @param data The text to be colored.
     * @param color The color for the text.
     * @return A colored string.
     */
    public static SpannableString colorString(String data, int color) {
        SpannableString ret = new SpannableString(data);
        ret.setSpan(new ForegroundColorSpan(color),
                0, data.length(), 0);
        return ret;
    }

    /**
     * Copy a text to the Android clipboard.
     * @param text The text that should by stored on the clipboard.
     * @param context Context of the SystemService
     * (and the Toast message that will by shown).
     * @param showMsg Show a "Copied to clipboard" message.
     */
    public static void copyToClipboard(String text, Context context,
                                       boolean showMsg) {
        if (!text.equals("")) {
            android.content.ClipboardManager clipboard =
                    (android.content.ClipboardManager)
                    context.getSystemService(
                            Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip =
                    android.content.ClipData.newPlainText(
                            "MIFARE classic tool data", text);
            clipboard.setPrimaryClip(clip);
            if (showMsg) {
                Toast.makeText(context, R.string.info_copied_to_clipboard,
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Get the content of the Android clipboard (if it is plain text).
     * @param context Context of the SystemService
     * @return The content of the Android clipboard. On error
     * (clipboard empty, clipboard content not plain text, etc.) null will
     * be returned.
     */
    public static String getFromClipboard(Context context) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager)
                context.getSystemService(
                        Context.CLIPBOARD_SERVICE);
        if (clipboard.getPrimaryClip() != null
                && clipboard.getPrimaryClip().getItemCount() > 0
                && clipboard.getPrimaryClipDescription().hasMimeType(
                    android.content.ClipDescription.MIMETYPE_TEXT_PLAIN)
                && clipboard.getPrimaryClip().getItemAt(0) != null
                && clipboard.getPrimaryClip().getItemAt(0)
                    .getText() != null) {
            return clipboard.getPrimaryClip().getItemAt(0)
                    .getText().toString();
        }

        // Error.
        return null;
    }

    /**
     * Share a file from the "tmp" directory as attachment.
     * @param context The context the FileProvider and the share intent.
     * @param file The file to share (from the "tmp" directory).
     * @see #TMP_DIR
     */
    public static void shareTmpFile(Context context, File file) {
        // Share file.
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
        } catch (IllegalArgumentException ex) {
            Toast.makeText(context, R.string.info_share_error,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        intent.setDataAndType(uri, "text/plain");
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        context.startActivity(Intent.createChooser(intent,
                context.getText(R.string.dialog_share_title)));
    }

    /**
     * Copy file.
     * @param in Input file (source).
     * @param out Output file (destination).
     * @throws IOException Error upon coping.
     */
    public static void copyFile(InputStream in, OutputStream out)
            throws IOException {
        byte[] buffer = new byte[1024];
        int read;
        while((read = in.read(buffer)) != -1){
          out.write(buffer, 0, read);
        }
    }

    /**
     * Convert Dips to pixels.
     * @param dp Dips.
     * @return Dips as px.
     */
    public static int dpToPx(int dp) {
        return (int) (dp * mScale + 0.5f);
    }

    /**
     * Get the current active (last detected) Tag.
     * @return The current active Tag.
     * @see #mTag
     */
    public static Tag getTag() {
        return mTag;
    }

    /**
     * Set the new active Tag (and update {@link #mUID}).
     * @param tag The new Tag.
     * @see #mTag
     * @see #mUID
     */
    public static void setTag(Tag tag) {
        mTag = tag;
        mUID = tag.getId();
    }

    /**
     * Get the App wide used NFC adapter.
     * @return NFC adapter.
     */
    public static NfcAdapter getNfcAdapter() {
        return mNfcAdapter;
    }

    /**
     * Set the App wide used NFC adapter.
     * @param nfcAdapter The NFC adapter that should be used.
     */
    public static void setNfcAdapter(NfcAdapter nfcAdapter) {
        mNfcAdapter = nfcAdapter;
    }

    /**
     * Remember the choice whether to use MCT in editor only mode or not.
     * @param value True if the user wants to use MCT in editor only mode.
     */
    public static void setUseAsEditorOnly(boolean value) {
        mUseAsEditorOnly = value;
    }

    /**
     * Get the key map generated by
     * {@link de.syss.MifareClassicTool.Activities.KeyMapCreator}.
     * @return A key map (see {@link MCReader#getKeyMap()}).
     */
    public static SparseArray<byte[][]> getKeyMap() {
        return mKeyMap;
    }

    /**
     * Set {@link #mKeyMapFrom} and {@link #mKeyMapTo}.
     * The {@link de.syss.MifareClassicTool.Activities.KeyMapCreator} will do
     * this for every created key map.
     * @param from {@link #mKeyMapFrom}
     * @param to {@link #mKeyMapTo}
     */
    public static void setKeyMapRange (int from, int to){
        mKeyMapFrom = from;
        mKeyMapTo = to;
    }

    /**
     * Get the key map start point.
     * @return {@link #mKeyMapFrom}
     */
    public static int getKeyMapRangeFrom() {
        return mKeyMapFrom;
    }

    /**
     * Get the key map end point
     * @return {@link #mKeyMapTo}
     */
    public static int getKeyMapRangeTo() {
        return mKeyMapTo;
    }

    /**
     * Set the key map.
     * @param value A key map (see {@link MCReader#getKeyMap()}).
     */
    public static void setKeyMap(SparseArray<byte[][]> value) {
        mKeyMap = value;
    }

    /**
     * Set the compnent name of a new pending activity.
     * @param pendingActivity The new pending activities component name.
     * @see #mPendingComponentName
     */
    public static void setPendingComponentName(ComponentName pendingActivity) {
        mPendingComponentName = pendingActivity;
    }

    /**
     * Get the component name of the current pending activity.
     * @return The compnent name of the current pending activity.
     * @see #mPendingComponentName
     */
    public static ComponentName getPendingComponentName() {
        return mPendingComponentName;
    }

    /**
     * Get the UID of the current tag.
     * @return The UID of the current tag.
     * @see #mUID
     */
    public static byte[] getUID() {
        return mUID;
    }

    /**
     * Check whether the provided BCC is valid for the UID or not. The BCC
     * is the first byte after the UID in the manufacturers block. It
     * is calculated by XOR-ing all bytes of the UID.
     * @param uid The UID to calculate the BCC from.
     * @param bcc The BCC the calculated BCC gets compared with.
     * @return True if the BCC if valid for the UID. False otherwise.
     */
    public static boolean isValidBCC(byte[] uid, byte bcc) {
        return calcBCC(uid) == bcc;
    }

    /**
     * Calculate the BCC of a 4 byte UID. For tags with a 4 byte UID the
     * BCC is the first byte after the UID in the manufacturers block.
     * It is calculated by XOR-ing the 4 bytes of the UID.
     * @param uid The UID of which the BCC should be calculated.
     * @exception IllegalArgumentException Thrown if the uid parameter
     * has not 4 bytes.
     * @return The BCC of the given UID.
     */
    public static byte calcBCC(byte[] uid) throws IllegalArgumentException {
        if (uid.length != 4) {
            throw new IllegalArgumentException("UID length is not 4 bytes.");
        }
        byte bcc = uid[0];
        for(int i = 1; i < uid.length; i++) {
            bcc = (byte)(bcc ^ uid[i]);
        }
        return bcc;
    }

    /**
     * Get the version code.
     * @return The version code.
     */
    public static String getVersionCode() {
        return mVersionCode;
    }

    /**
     * If NFC is disabled and the user chose to use MCT in editor only mode,
     * this method will return true.
     * @return True if the user wants to use MCT in editor only mode.
     * False otherwise.
     */
    public static boolean useAsEditorOnly() {
        return mUseAsEditorOnly;
    }


}
