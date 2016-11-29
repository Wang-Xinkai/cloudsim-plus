/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */
package org.cloudbus.cloudsim.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;

import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.DataCloudTags;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModel;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelFull;

/**
 * This class is responsible for reading resource traces from a file and
 * creating a list of ({@link Cloudlet Cloudlets}) (jobs). By default, it follows
 * the Standard Workload Format (*.swf files) from
 * <a href="http://www.cs.huji.ac.il/labs/parallel/workload/">
 * The Hebrew University of Jerusalem</a>. However, you can use other formats by
 * calling the methods below before running the simulation:
 * <ul>
 * <li> {@link #setComment(String)}
 * <li> {@link #setField(int, int, int, int, int)}
 * </ul>
 * <p>
 * <b>NOTES:</b>
 * <ul>
 * <li>This class can only take <tt>one</tt> trace file of the following format:
 * <i>ASCII text, zip, gz.</i>
 * <li>If you need to load multiple trace files, then you need to create
 * multiple instances of this class <tt>each with a unique entity name</tt>.
 * <li>If size of the trace file is huge or contains lots of traces, please
 * increase the JVM heap size accordingly by using <tt>java -Xmx</tt> option
 * when running the simulation.
 * <li>The default Cloudlet file size for sending to and receiving from a Datacenter is
 * {@link DataCloudTags#DEFAULT_MTU}. However, you can
 * specify the file size by using {@link Cloudlet#setCloudletFileSize(long)}.
 * <li>A job run time is only for 1 PE <tt>not</tt> the total number of
 * allocated PEs. Therefore, a Cloudlet length is also calculated for 1 PE.<br>
 * For example, job #1 in the trace has a run time of 100 seconds for 2
 * processors. This means each processor runs job #1 for 100 seconds, if the
 * processors have the same specification.
 * </ul>
 * </p>
 *
 * @todo The last item in the list above is not true. The cloudlet length is not
 * divided by the number of PEs. If there is more than 1 PE, all PEs run the
 * same number of MI as specified in the {@link Cloudlet#getCloudletLength()}
 * attribute. See {@link Cloudlet#setNumberOfPes(int)} method documentation.
 *
 *
 * @author Anthony Sulistio
 * @author Marcos Dias de Assuncao
 *
 * @see WorkloadModel
 */
public class WorkloadFileReader implements WorkloadModel {

    /**
     * Trace file name.
     */
    private final File file;

    /**
     * The Cloudlet's PE rating (in MIPS), considering that all PEs of a
     * Cloudlet have the same rate.
     */
    private final int rating;

    /**
     * List of Cloudlets created from the trace {@link #file}.
     */
    private List<Cloudlet> jobs;

    /* Index of fields from the Standard Workload Format. */
    /**
     * Field index of job number.
     */
    private int JOB_NUM = 1 - 1;

    /**
     * Field index of submit time of a job.
     */
    private int SUBMIT_TIME = 2 - 1;

    /**
     * Field index of running time of a job.
     */
    private final int RUN_TIME = 4 - 1;

    /**
     * Field index of number of processors needed for a job.
     */
    private final int NUM_PROC = 5 - 1;

    /**
     * Field index of required number of processors.
     */
    private int REQ_NUM_PROC = 8 - 1;

    /**
     * Field index of required running time.
     */
    private int REQ_RUN_TIME = 9 - 1;

    /**
     * Field index of user who submitted the job.
     */
    private final int USER_ID = 12 - 1;

    /**
     * Field index of group of the user who submitted the job.
     */
    private final int GROUP_ID = 13 - 1;

    /**
     * Max number of fields in the trace file.
     */
    private int MAX_FIELD = 18;

    /**
     * A string that denotes the start of a comment.
     */
    private String COMMENT = ";";

    /**
     * If the field index of the job number ({@link #JOB_NUM}) is equals to this
     * constant, it means the number of the job doesn't have to be gotten from
     * the trace file, but has to be generated by this workload generator class.
     */
    private static final int IRRELEVANT = -1;

    /**
     * A temp array storing all the fields read from a line of the trace file.
     */
    private String[] fieldArray = null;

    /**
     * @see #getMaxNumberOfLinesToRead()
     */
    private int maxNumberOfLinesToRead;

    /**
     * Create a new WorkloadFileReader object.
     *
     * @param fileName the workload trace filename in one of the following
     * formats:
     * <i>ASCII text, zip, gz.</i>
     * @param rating the cloudlet's PE rating (in MIPS), considering that all
     * PEs of a cloudlet have the same rate
     * @throws FileNotFoundException
     * @throws IllegalArgumentException This happens for the following
     * conditions:
     * <ul>
     * <li>the workload trace file name is null or empty
     * <li>the resource PE rating <= 0 </ul> @pre fileName != null
     * @pre rating > 0
     * @post $none
     */
    public WorkloadFileReader(final String fileName, final int rating) throws FileNotFoundException {
        if (fileName == null || fileName.length() == 0) {
            throw new IllegalArgumentException("Invalid trace file name.");
        } else if (rating <= 0) {
            throw new IllegalArgumentException("Resource PE rating must be > 0.");
        }

        file = new File(fileName);
        if (!file.exists()) {
            throw new FileNotFoundException("Workload trace " + fileName + " does not exist");
        }

        this.jobs = new ArrayList<>();
        this.rating = rating;
        this.maxNumberOfLinesToRead = -1;
    }

    /**
     * Reads job information from a trace file and generates the respective
     * cloudlets.
     *
     * @return the list of cloudlets read from the file or an empty list in
     * case of failure.
     * @see #file
     */
    @Override
    public List<Cloudlet> generateWorkload() {
        if (jobs.isEmpty()) {
            // create a temp array
            fieldArray = new String[MAX_FIELD];

            try {
                /*@todo It would be implemented
                            using specific classes to avoid using ifs.
                            If a new format is included, the code has to be
                            changed to include another if*/
                if (file.getName().endsWith(".gz")) {
                    readGZIPFile(file);
                } else if (file.getName().endsWith(".zip")) {
                    readZipFile(file);
                } else {
                    readTextFile(file);
                }
            } catch (final FileNotFoundException e) {
            } catch (final IOException e) {
            }
        }

        return jobs;
    }

    /**
     * Sets the string that identifies the start of a comment line.
     *
     * @param cmt a character that denotes the start of a comment, e.g. ";" or
     * "#"
     * @return <code>true</code> if it is successful, <code>false</code>
     * otherwise
     * @pre comment != null
     * @post $none
     */
    public boolean setComment(final String cmt) {
        boolean success = false;
        if (cmt != null && cmt.length() > 0) {
            COMMENT = cmt;
            success = true;
        }
        return success;
    }

    /**
     * Tells this class what to look in the trace file. This method should be
     * called before the start of the simulation.
     * <p/>
     * By default, this class follows the standard workload format as specified
     * in <a
     * href="http://www.cs.huji.ac.il/labs/parallel/workload/">
     * http://www.cs.huji.ac.il/labs/parallel/workload/</a> <br>
     * However, you can use other format by calling this method.
     * <p/>
     * The parameters must be a positive integer number starting from 1. A
     * special case is where
     * <tt>jobNum == {@link #IRRELEVANT}</tt>, meaning the job or cloudlet ID
     * will be generate by the Workload class, instead of reading from the trace
     * file.
     *
     * @param maxField max. number of field/column in one row
     * @param jobNum field/column number for locating the job ID
     * @param submitTime field/column number for locating the job submit time
     * @param runTime field/column number for locating the job run time
     * @param numProc field/column number for locating the number of PEs
     * required to run a job
     * @return <code>true</code> if successful, <code>false</code> otherwise
     * @throws IllegalArgumentException if any of the arguments are not within
     * the acceptable ranges
     * @pre maxField > 0
     * @pre submitTime > 0
     * @pre runTime > 0
     * @pre numProc > 0
     * @post $none
     */
    public boolean setField(
            final int maxField,
            final int jobNum,
            final int submitTime,
            final int runTime,
            final int numProc) {
        // need to subtract by 1 since array starts at 0.
        if (jobNum > 0) {
            JOB_NUM = jobNum - 1;
        } else if (jobNum == 0) {
            throw new IllegalArgumentException("Invalid job number field.");
        } else {
            JOB_NUM = -1;
        }

        // get the max. number of field
        if (maxField > 0) {
            MAX_FIELD = maxField;
        } else {
            throw new IllegalArgumentException("Invalid max. number of field.");
        }

        // get the submit time field
        if (submitTime > 0) {
            SUBMIT_TIME = submitTime - 1;
        } else {
            throw new IllegalArgumentException("Invalid submit time field.");
        }

        // get the run time field
        if (runTime > 0) {
            REQ_RUN_TIME = runTime - 1;
        } else {
            throw new IllegalArgumentException("Invalid run time field.");
        }

        // get the number of processors field
        if (numProc > 0) {
            REQ_NUM_PROC = numProc - 1;
        } else {
            throw new IllegalArgumentException("Invalid number of processors field.");
        }

        return true;
    }

    /**
     * Creates a Cloudlet with the given information and adds to the list of
     * {@link #jobs}.
     *
     * @param id a Cloudlet ID
     * @param submitTime Cloudlet's submit time
     * @param runTime The number of seconds the Cloudlet has to run. Considering
     * that and the {@link #rating}, the {@link Cloudlet#getCloudletLength()} is
     * computed.
     * @param numProc number of Cloudlet's PEs
     * @param reqRunTime user estimated run time (@todo the parameter is not
     * being used and it is not clear what it is)
     * @param userID user id
     * @param groupID user's group id
     * @pre id >= 0
     * @pre submitTime >= 0
     * @pre runTime >= 0
     * @pre numProc > 0
     * @post $none
     * @see #rating
     */
    private void createJob(
            final int id,
            final long submitTime,
            final int runTime,
            final int numProc,
            final int reqRunTime,
            final int userID,
            final int groupID) {
        final int len = runTime * rating;
        UtilizationModel utilizationModel = new UtilizationModelFull();
        final Cloudlet cloudlet = new CloudletSimple(id,  len,  numProc)
                .setCloudletFileSize(DataCloudTags.DEFAULT_MTU)
                .setCloudletOutputSize(DataCloudTags.DEFAULT_MTU)
                .setUtilizationModel(utilizationModel);
        jobs.add(cloudlet);
    }

    /**
     * Extracts relevant information from a given array of fields, representing
     * a line from the trace file, and creates a cloudlet using this
     * information.
     *
     * @param array the array of fields generated from a line of the trace file.
     * @param line the line number
     * @pre array != null
     * @pre line > 0
     */
    private void createCloudletFromOneTraceFileLine(final String[] array, final int line) {
        try {
            Integer obj = null;

            // get the job number
            int id = 0;
            if (JOB_NUM == IRRELEVANT) {
                id = jobs.size() + 1;
            } else {
                obj = new Integer(array[JOB_NUM].trim());
                id = obj;
            }

            // get the submit time
            final Long l = new Long(array[SUBMIT_TIME].trim());
            final long submitTime = l.intValue();

            // get the user estimated run time
            obj = new Integer(array[REQ_RUN_TIME].trim());
            final int reqRunTime = obj;

            // if the required run time field is ignored, then use
            // the actual run time
            obj = new Integer(array[RUN_TIME].trim());
            int runTime = obj;

            final int userID = new Integer(array[USER_ID].trim());
            final int groupID = new Integer(array[GROUP_ID].trim());

            // according to the SWF manual, runtime of 0 is possible due
            // to rounding down. E.g. runtime is 0.4 seconds -> runtime = 0
            if (runTime <= 0) {
                runTime = 1; // change to 1 second
            }

            // get the number of allocated processors
            obj = new Integer(array[REQ_NUM_PROC].trim());
            int numProc = obj;

            // if the required num of allocated processors field is ignored
            // or zero, then use the actual field
            if (numProc == IRRELEVANT || numProc == 0) {
                obj = new Integer(array[NUM_PROC].trim());
                numProc = obj;
            }

            // finally, check if the num of PEs required is valid or not
            if (numProc <= 0) {
                numProc = 1;
            }
            createJob(id, submitTime, runTime, numProc, reqRunTime, userID, groupID);
        } catch (final Exception e) {

        }
    }

    /**
     * Breaks a line from the trace file into many fields into the
     * {@link #fieldArray}.
     *
     * @param line a line from the trace file
     * @param lineNum the line number
     * @return true if the line was parsed, false otherwise
     *
     * @pre line != null
     * @pre lineNum > 0
     * @post $none
     */
    private boolean parseValue(final String line, final int lineNum) {
        // skip a comment line
        if (line.startsWith(COMMENT)) {
            return false;
        }

        final String[] sp = line.split("\\s+"); // split the fields based on a
        // space
        int len = 0; // length of a string
        int index = 0; // the index of an array

        // check for each field in the array
        for (final String elem : sp) {
            len = elem.length(); // get the length of a string

            // if it is empty then ignore
            if (len == 0) {
                continue;
            }
            fieldArray[index] = elem;
            index++;
        }

        if (index == MAX_FIELD) {
            createCloudletFromOneTraceFileLine(fieldArray, lineNum);
        }

        return true;
    }

    /**
     * Reads traces from a InputStream to a workload file
     * in any supported format.
     *
     * @param inputStream the stream that is able to read data from a workload file
     * @return <code>true</code> if successful, <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the file
     * @throws FileNotFoundException if the file was not found
     */
    private boolean readFile(final InputStream inputStream) throws IOException, FileNotFoundException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            int line = 1;
            String readLine;
            while ((readLine = readNextLine(reader, line)) != null) {
                parseValue(readLine, line);
                line++;
            }

            return true;
        }
    }

    /**
     * Reads traces from a text file, usually with the swf extension, one line at a time.
     *
     * @param fl a file name
     * @return <code>true</code> if successful, <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the file
     * @throws FileNotFoundException if the file was not found
     */
    protected boolean readTextFile(final File fl) throws IOException, FileNotFoundException {
        return readFile(new FileInputStream(fl));
    }

    /**
     * Reads traces from a gzip file, one line at a time.
     *
     * @param fl a gzip file name
     * @return <code>true</code> if successful; <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the file
     * @throws FileNotFoundException if the file was not found
     */
    protected boolean readGZIPFile(final File fl) throws IOException, FileNotFoundException {
        return readFile(new GZIPInputStream(new FileInputStream(fl)));
    }

    /**
     * Reads a set of trace files inside a Zip file.
     *
     * @param fl a zip file name
     * @return <code>true</code> if reading a file is successful;
     * <code>false</code> otherwise.
     * @throws IOException if the there was any error reading the file
     */
    protected boolean readZipFile(final File fl) throws IOException {
        try(ZipFile zipFile = new ZipFile(fl)) {
            // ZipFile offers an Enumeration of all the files in the file
            final Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
            while (zipEntries.hasMoreElements()) {
                final ZipEntry zipEntry = zipEntries.nextElement();
                readFile(zipFile.getInputStream(zipEntry));
            }
            return true;
        }
    }

    /**
     * Reads the next line of the workload file.
     *
     * @param reader the object that is reading the workload file
     * @param lineNumber the number of the line that that will be read from the workload file
     * @return the line read; or null if there isn't any more lines to read or if
     * the number of lines read reached the {@link #getMaxNumberOfLinesToRead()}
     */
    private String readNextLine(BufferedReader reader, int lineNumber) throws IOException {
        if(reader.ready() && (maxNumberOfLinesToRead == -1 || lineNumber <= maxNumberOfLinesToRead))
            return reader.readLine();

        return null;
    }

    /**
     * Gets the maximum number of lines of the workload file that will be read.
     * The value -1 indicates that all lines will be read, creating
     * a cloudlet from every one.
     *
     * @return
     */
    public int getMaxNumberOfLinesToRead() {
        return maxNumberOfLinesToRead;
    }

    /**
     * Sets the maximum number of lines of the workload file that will be read.
     * The value -1 indicates that all lines will be read, creating
     * a cloudlet from every one.
     *
     * @param maxNumberOfLinesToRead
     */
    public void setMaxNumberOfLinesToRead(int maxNumberOfLinesToRead) {
        this.maxNumberOfLinesToRead = maxNumberOfLinesToRead;
    }
}
