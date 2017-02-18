package com.femtoduino.femtobeaconserver;
/**
 *  Requires External Storage Read/Write and USB Device Attach/Detach permissions enabled!
 */
/* Copyright 2011-2013 Google Inc.
 * Copyright 2013 mike wakerly <opensource@hoho.com>
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
 * USA.
 *
 * Project home page: https://github.com/mik3y/usb-serial-for-android
 */

        import android.app.Activity;
        import android.content.Context;
        import android.content.Intent;
        import android.hardware.usb.UsbDeviceConnection;
        import android.hardware.usb.UsbManager;
        import android.os.Bundle;
        import android.os.Environment;
        import android.util.Log;
        import android.widget.CheckBox;
        import android.widget.CompoundButton;
        import android.widget.ScrollView;
        import android.widget.TextView;

        import com.hoho.android.usbserial.driver.UsbSerialPort;
        import com.hoho.android.usbserial.util.HexDump;
        import com.hoho.android.usbserial.util.SerialInputOutputManager;

        import java.io.BufferedInputStream;
        import java.io.BufferedOutputStream;
        import java.io.FileInputStream;
        import java.io.FileNotFoundException;
        import java.io.FileOutputStream;
        import java.io.IOException;
        import java.io.OutputStream;
        import java.io.OutputStreamWriter;
        import java.nio.ByteBuffer;
        import java.nio.channels.FileChannel;
        import java.util.ArrayList;
        import java.util.Arrays;
        import java.util.Collections;
        import java.util.List;
        import java.util.concurrent.ExecutorService;
        import java.util.concurrent.Executors;
        import java.io.File;
        import java.util.zip.ZipEntry;
        import java.util.zip.ZipOutputStream;

/**
 * Monitors a single {@link UsbSerialPort} instance, showing all data
 * received.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialConsoleActivity extends Activity {

    private static final String TAG = SerialConsoleActivity.class.getSimpleName();
    private static final Integer PAGING_SIZE = 4000; // About 4KB
    private static final Integer MAX_FILE_SIZE = 33000; //33KB
    private static final Integer ZIP_BUFFER_PADDING_SIZE = 5000;//5KB
    private static final Integer ZIP_BUFFER_SIZE = MAX_FILE_SIZE + ZIP_BUFFER_PADDING_SIZE;
    private static final Integer MAX_PARTS_PER_ZIP = 10;

    /**
     * Driver instance, passed in statically via
     * {@link #show(Context, UsbSerialPort)}.
     *
     * <p/>
     * This is a devious hack; it'd be cleaner to re-create the driver using
     * arguments passed in with the {@link #startActivity(Intent)} intent. We
     * can get away with it because both activities will run in the same
     * process, and this is a simple demo.
     */
    private static UsbSerialPort sPort = null;

    private TextView mTitleTextView;
    private TextView mDumpTextView;
    private ScrollView mScrollView;
    private CheckBox chkDTR;
    private CheckBox chkRTS;
    private CheckBox toMicroSD;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    SerialConsoleActivity.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            SerialConsoleActivity.this.updateReceivedData(data);
                        }
                    });
                }
            };

    private volatile static Integer partIndex = 0;
    private static Integer oldPartIndex = partIndex;
    private volatile static String sessionTimestamp;

    private static long writePosition = 0;


    private volatile static List<FileOutputStream> fileOutputStream = new ArrayList<FileOutputStream>();
    private volatile static List<FileChannel> fileChannel = new ArrayList<FileChannel>();
    private volatile static Integer fileChannelIndex = 0;
    private volatile static List<Long> lastFileSize = new ArrayList<Long>();
    private volatile static boolean isReadingSerial = false;
    private volatile static String serialLineBuffer = "";

    private static boolean saveToMicroSd = true;
    private static boolean canSaveToMicroSd = true;
    private static String sdCardPath = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.serial_console);
        mTitleTextView = (TextView) findViewById(R.id.demoTitle);
        mDumpTextView = (TextView) findViewById(R.id.consoleText);
        mScrollView = (ScrollView) findViewById(R.id.demoScroller);
        chkDTR = (CheckBox) findViewById(R.id.checkBoxDTR);
        chkRTS = (CheckBox) findViewById(R.id.checkBoxRTS);
        toMicroSD = (CheckBox) findViewById(R.id.toMicroSd);

        chkDTR.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    sPort.setDTR(isChecked);
                }catch (IOException x){}
            }
        });

        chkRTS.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    sPort.setRTS(isChecked);
                }catch (IOException x){}
            }
        });

        toMicroSD.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                saveToMicroSd = isChecked;
                checkMicroSD();

            }
        });

        checkMicroSD();

        setSessionPartIndex();
        setSessionTimestamp();
        openDataChannel();

        if (isExternalStorageWritable()) {
            Log.d(TAG, "EXTERNAL Storage is writable.");
        } else {
            Log.e(TAG, "EXTERNAL STORAGE is not writable!");
        }



//        openDataChannel();
    }


    @Override
    protected void onPause() {
        super.onPause();
        stopIoManager();
        if (sPort != null) {
            try {
                sPort.close();
            } catch (IOException e) {
                // Ignore.
            }
            sPort = null;
        }
        finish();
    }

    void showStatus(TextView theTextView, String theLabel, boolean theValue){
        String msg = theLabel + ": " + (theValue ? "enabled" : "disabled") + "\n";
        theTextView.append(msg);
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkMicroSD();

        setSessionTimestamp();
        setSessionPartIndex();
        Log.d(TAG, "Resumed, port=" + sPort);
        if (sPort == null) {
            mTitleTextView.setText("No serial device.");
        } else {
            final UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

            UsbDeviceConnection connection = usbManager.openDevice(sPort.getDriver().getDevice());
            if (connection == null) {
                mTitleTextView.setText("Opening device failed");
                return;
            }

            try {
                sPort.open(connection);
                sPort.setParameters(500000, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                showStatus(mDumpTextView, "CD  - Carrier Detect", sPort.getCD());
                showStatus(mDumpTextView, "CTS - Clear To Send", sPort.getCTS());
                showStatus(mDumpTextView, "DSR - Data Set Ready", sPort.getDSR());
                showStatus(mDumpTextView, "DTR - Data Terminal Ready", sPort.getDTR());
                showStatus(mDumpTextView, "DSR - Data Set Ready", sPort.getDSR());
                showStatus(mDumpTextView, "RI  - Ring Indicator", sPort.getRI());
                showStatus(mDumpTextView, "RTS - Request To Send", sPort.getRTS());

            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                mTitleTextView.setText("Error opening device: " + e.getMessage());
                try {
                    sPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                sPort = null;
                return;
            }
            mTitleTextView.setText("Serial device: " + sPort.getClass().getSimpleName());
        }
        onDeviceStateChange();
    }

    private void stopIoManager() {

        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
        isReadingSerial = false;

        closeDataChannel(); // don't flush. Let it finish

        mDumpTextView.append("Stopped recording.");
        mScrollView.scrollTo(0, mDumpTextView.getBottom());
    }

    private void startIoManager() {
        isReadingSerial = true;
//        openDataChannel();

        if (sPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
            mExecutor.submit(mSerialIoManager);

            mDumpTextView.append("Started recording.");
            mScrollView.scrollTo(0, mDumpTextView.getBottom());
        }
    }

    private void onDeviceStateChange() {
        checkMicroSD();
        stopIoManager();
        startIoManager();
    }

    private void setSessionTimestamp() {
        sessionTimestamp = getTimestamp().toString();
    }

    private static Long getTimestamp() {
        Long tsTime = System.currentTimeMillis() / 1000;
        return tsTime;
    }

    private void setSessionPartIndex() {
        partIndex = 0;
    }

    private void checkMicroSD() {

        canSaveToMicroSd = saveToMicroSd == true && isExternalStorageReadable() && isExternalStorageWritable();
    }

    private void updateReceivedData(byte[] data) {
        final String message = new String(ByteBuffer.wrap(data).array());// HexDump.dumpHexString(data) + "\n";
//        Log.d(TAG, "updateReceivedData() got: " + message);
        serialLineBuffer += message;

        if (data.length < 1) {
            return;
        }
        if ( message.contains("\n") && serialLineBuffer.length() >= PAGING_SIZE) {
//            Log.d(TAG, "SAVING");
            saveDataChannelAsync();
        }

        mDumpTextView.append(message);

        Integer currentTextLength = mDumpTextView.getText().length();

        if (currentTextLength > 1024) {
            CharSequence dump = mDumpTextView.getText();
            Integer len = dump.length();

            Log.d(TAG, "Dump text view, grabbing subsequence. Current len is " + currentTextLength + ", subsequence from 1024 to " + (len - 1));
            CharSequence newText = dump.subSequence(1024, len - 1 );
            Log.d(TAG, "Dump text view, new text OK. Length is " + newText.length());

            mDumpTextView.setText(newText);

//            mScrollView.requestFocus();
        }




        mScrollView.scrollTo(0, mDumpTextView.getBottom());

    }

    /**
     * Starts the activity, using the supplied driver instance.
     *
     * @param context
     * @param driver
     */
    static void show(Context context, UsbSerialPort port) {
        sPort = port;
        final Intent intent = new Intent(context, SerialConsoleActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_HISTORY);
        context.startActivity(intent);
    }


    private boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state)) {
            return true;
        }

        return false;
    }

    private boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        if (Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            return true;
        }

        return false;
    }

    public static void openDataChannel() {

        boolean enableAppendMode = true;
        Integer index = fileChannelIndex;

        if (fileOutputStream.size() - 1 < index || fileOutputStream.get(index) == null)
        {
            String filePath = getDataFileName();

            try {
                Log.d(TAG, "Attempting to create file output stream (append mode) to " + filePath);
                fileOutputStream.add(new FileOutputStream(filePath, enableAppendMode));
            } catch (FileNotFoundException fnfe) {
                Log.e(TAG, "File not found! " + filePath + ", " + fnfe.getMessage());
            }
        }

        if (fileChannel.size() - 1 < index || fileChannel.get(index) == null) {
            fileChannel.add(fileOutputStream.get(index).getChannel());
        }

        if (lastFileSize.size() - 1 < index || lastFileSize.get(index) == null) {
            lastFileSize.add(Long.valueOf(0));
        }

    }
    private static void saveDataChannelAsync() {
        ExecutorService executor = Executors.newCachedThreadPool();
        while (true == isReadingSerial) {
            if (serialLineBuffer.length() <= 0) {
                break;
            }
            Runnable genuineWorker = new Runnable() {
                @Override
                public void run() {
                    saveDataChannel();
                }
            };
            executor.execute(genuineWorker);
        }

        executor.shutdown();
        while (!executor.isTerminated()) {
            // Wait for executor to terminate...
            Log.d(TAG, "Waiting for executor to terminate...");
        }

        //closeDataChannel();

    }
    public static int saveDataChannel() {
        int numBytesWritten = 0;


//        if (
//                fileChannelIndex >= fileChannel.size() ||
//                fileChannelIndex >= fileOutputStream.size() ||
//                fileChannel.get(fileChannelIndex) == null ||
//                fileOutputStream.get(fileChannelIndex) == null ) {

//            Log.d(TAG, "Attempting to open data channel at index " + fileChannelIndex);
            openDataChannel();
//        }

        if (fileChannel.size() > fileChannelIndex && fileChannel.get(fileChannelIndex) != null) {

            try {
//                if (fileChannel[fileChannelIndex] == null) {
////                    closeDataChannel();
//                    openDataChannel();
//
//                    Log.d(TAG, "Closed and opened data channel, as no file channel was detected");
//                }

//                Log.d(TAG, "saveDataChannel() on channel index (" + fileChannelIndex + ") Last File size was " + lastFileSize.get(fileChannelIndex) );

                if (lastFileSize.get(fileChannelIndex) + serialLineBuffer.length() > MAX_FILE_SIZE ) {
//                    Log.d(TAG, "Data is: " + buffer.toString());

                    if (serialLineBuffer.contains("\n")) {

                        if (serialLineBuffer.endsWith( "\n")) {
                            // Save as is, since it ends with new line char.
                            numBytesWritten = writeData(ByteBuffer.wrap(serialLineBuffer.getBytes()) );
                            // Close out the part file, don't flush. A new part file will be generated if new data comes in.
                            closeDataChannel();
                            ++fileChannelIndex;

                            // Increment the part counter.
                            ++partIndex;

                            serialLineBuffer = "";

                            attemptToZip();
                        } else {
                            // Split at new line char. complete the part file, begin the next one.

                            Integer pos = serialLineBuffer.lastIndexOf("\n");
                            byte[] leftPart = serialLineBuffer.substring(0, pos).getBytes();
                            byte[] rightPart = serialLineBuffer.substring(pos + 1).getBytes();

                            String completedFile = getDataFileName();

                            numBytesWritten = writeData(ByteBuffer.wrap(leftPart));
                            Log.d(TAG, "Wrote serial line to disk, part " + partIndex + " (" + numBytesWritten + " bytes)");
                            Log.d(TAG, "Completed a part file, " + completedFile);
                            closeDataChannel(); // Close, and flush since we now need a new part file!

                            // Increment the part counter, then generate a new part file to begin saving the remaining partial data.
                            lastFileSize.set(fileChannelIndex, new Long(0));

                            ++partIndex;
                            ++fileChannelIndex;

                            openDataChannel();

                            // Should have a new part file name.
                            String nextFile = getDataFileName();
                            Log.d(TAG, "Next part file will be " + nextFile);
//                            numBytesWritten += writeData(ByteBuffer.wrap(rightPart));

                            // Preset the serial line buffer to the truncated part, so we can continue appending from there.
                            serialLineBuffer = new String(ByteBuffer.wrap(rightPart).array());

                            attemptToZip();
                        }

                    }
                } else {
                    // Keep writing to current file, one line at a time;
//                    Log.d(TAG, "Serial Line Buffer: " + serialLineBuffer);

                    if (serialLineBuffer.endsWith("\n")) {
                        // Save as is, since it ends with new line char.
                        numBytesWritten = writeData(ByteBuffer.wrap(serialLineBuffer.getBytes()));
                        serialLineBuffer = "";

                        lastFileSize.set(fileChannelIndex, lastFileSize.get(fileChannelIndex) + numBytesWritten);
                        serialLineBuffer = "";

                        Log.d(TAG, "Wrote serial line to disk, part " + partIndex + " (" + numBytesWritten + " bytes)");
                    }

                    if (serialLineBuffer.length() > 1000000) { // Check buffer if greater than 1MB size.
                        // Hmmm... no new line?!
                        serialLineBuffer = ""; // Discard to prevent overflow... We need new line delimited data for this app.
                        Log.w(TAG, "Warning! Serial data contains no new line characters! Buffer is filling up, but no data is saved!");
                    }
                }
            } catch (Exception ioe) {
                Log.e(TAG, "saveDataChannel() failed! Error: ", ioe);
            }
        } else {
            Log.d(TAG, "No file channel available, even after attempting to open");
        }


        return numBytesWritten;
    }

    public static String getDataFileName() {
        return getDataFileName(partIndex);
    }
    public static String getDataFileName(Integer part_index) {
        String fileName = "femtobeacon." + sessionTimestamp.toString() +
                ".part" + part_index + ".csv";
        return getDataFileName(fileName);
    }
    public static String getDataFileName(String fileName) {




        File p = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "/femtobeacon");

        if (!p.exists()) {
            p.mkdirs();
        }

        File f = new File(p, fileName);
        try {
            f.createNewFile();

        } catch (IOException ioe) {
            Log.e(TAG, "getDataFileName() Could not create NEW file " + f.getPath().toString(), ioe);
        }
        Log.d(TAG, "getDataFileName() result is: " + f.getAbsolutePath());
        return f.getPath();
    }
    private static Integer writeData(ByteBuffer data) {
        Integer numBytesWritten = 0;

        try {
//            Log.d(TAG, "Calling fileChannel.write(data)!");
            if (fileChannelIndex > fileChannel.size() - 1 || !fileChannel.get(fileChannelIndex).isOpen()) {
                openDataChannel();
            }
            numBytesWritten = fileChannel.get(fileChannelIndex).write(data);
        } catch (IOException ioe) {
            Log.e(TAG, "Could not complete writeData() method!", ioe);
        }

        Log.d(TAG, "WROTE " + numBytesWritten + " BYTES");
        return numBytesWritten;
    }
    public static void closeDataChannel() {
        closeDataChannel(false, false);
    }
    public static void closeDataChannel(boolean flush) {
        closeDataChannel(flush, false);
    }
    public static void closeDataChannel(boolean flush, boolean destroyStream) {
        if (fileOutputStream.size() > fileChannelIndex) {
            try {

                if (fileOutputStream.get(fileChannelIndex) != null) {
                    if (flush == true) {
                        fileOutputStream.get(fileChannelIndex).flush();
                    }

                    fileOutputStream.get(fileChannelIndex).close();
                }
            } catch (IOException ie) {
                Log.e(TAG, "Could not close file output stream (" + fileChannelIndex + "). " + ie.getMessage());
            }
        } else {
            return;
        }

        if (fileChannel.size() > fileChannelIndex && fileChannel.get(fileChannelIndex) != null && fileChannel.get(fileChannelIndex).isOpen()) {
            try {
                if (fileChannel.get(fileChannelIndex).isOpen()) {
                    fileChannel.get(fileChannelIndex).close();
                }
            } catch (IOException ioe) {
                Log.e(TAG, "Could not close file channel (" + fileChannelIndex + "). " + ioe.getMessage());
            }
        }

        if (destroyStream) {
            fileChannel.set(fileChannelIndex, null);
            fileOutputStream.set(fileChannelIndex, null);
        }

        Integer index;
        for (index = 0; index < fileChannel.size(); index++) {
            if (fileChannel.get(index) != null && !fileChannel.get(index).isOpen()) {
                // Closed, we can set it to null.
                fileChannel.set(index, null);

                if (fileOutputStream.get(index) != null) {
                    fileOutputStream.set(index, null);
                }
                if (lastFileSize.get(index) != null) {
                    lastFileSize.set(index, null);
                }
            }
        }

        // Remove garbage and reindex
        fileChannel.removeAll(Collections.singleton(null));
        fileOutputStream.removeAll(Collections.singleton(null));
        lastFileSize.removeAll(Collections.singleton(null));

        if (fileChannel.size() > 0) {
            fileChannelIndex = fileChannel.size() - 1;
        } else {
            fileChannelIndex = 0;
        }
    }

    public static void attemptToZip() {
        if (partIndex - oldPartIndex < MAX_PARTS_PER_ZIP) {
            return;
        }

        Integer system_part_index;
        List<String> fileList = new ArrayList<String>();
        File p = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "/femtobeacon");

        for (system_part_index = oldPartIndex; system_part_index < partIndex; system_part_index++) {
            File systemPartFile = new File(p, getDataFileName(system_part_index));

            if (systemPartFile.exists()) {
                fileList.add(systemPartFile.getAbsolutePath());
            }
        }

        String[] file_list = {};
        fileList.toArray(file_list);

        try {
            zip(file_list, getDataFileName("femtobeacon" +  getTimestamp() + ".zip"));
        } catch (IOException ioe) {
            Log.e(TAG, "Could not zip files!", ioe);
        }
    }

    public static void zip(String[] files, String zipFile) throws IOException {
        BufferedInputStream origin = null;
        ZipOutputStream out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
        try {
            byte data[] = new byte[ZIP_BUFFER_SIZE];

            for (int i = 0; i < files.length; i++) {
                FileInputStream fi = new FileInputStream(files[i]);
                origin = new BufferedInputStream(fi, ZIP_BUFFER_SIZE);
                try {
                    ZipEntry entry = new ZipEntry(files[i].substring(files[i].lastIndexOf("/") + 1));
                    out.putNextEntry(entry);
                    int count;
                    while ((count = origin.read(data, 0, ZIP_BUFFER_SIZE)) != -1) {
                        out.write(data, 0, count);
                    }
                }
                finally {
                    origin.close();
                }
            }
        }
        finally {
            out.close();
        }
    }
/*
    private File getDataStorageDir(String fileName) {
        File path;
        File file;

        if (canSaveToMicroSd) {
//            String externalStorage = Environment.getExternalStorageDirectory().toString();
//            path = new File(externalStorage);
            path = getExternalFilesDir("femtobeacon/" );

        } else {
            path = Environment.getExternalStoragePublicDirectory("femtobeacon/");

        }

        if (!path.exists()) {
            path.mkdirs();
        }

        if (!path.mkdirs()) {
            Log.e(TAG, "Directory not created! " + path.toString());
        }

        file = new File(path, fileName);

        return file;
    }

    private void appendData(File file, String data) {
        try {
            checkMicroSD();


            FileOutputStream fOut = new FileOutputStream(file);
            OutputStreamWriter myOutWriter = new OutputStreamWriter(fOut);

            if (!file.exists()) {
                file.createNewFile();
                myOutWriter.write(data);
            } else {
                myOutWriter.append(data);
            }



            myOutWriter.close();

            fOut.flush();
            fOut.close();

        } catch (IOException e) {
            Log.w(TAG, "Error writing " + file, e);
        }
    }

    */

}