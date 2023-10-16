package de.jplag.reporting.reportobject;

import static de.jplag.reporting.jsonfactory.DirectoryManager.createDirectory;
import static de.jplag.reporting.jsonfactory.DirectoryManager.deleteDirectory;
import static de.jplag.reporting.jsonfactory.DirectoryManager.zipDirectory;
import static de.jplag.reporting.reportobject.mapper.SubmissionNameToIdMapper.buildSubmissionNameToIdMap;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.jplag.JPlag;
import de.jplag.JPlagComparison;
import de.jplag.JPlagResult;
import de.jplag.Language;
import de.jplag.Submission;
import de.jplag.reporting.FilePathUtil;
import de.jplag.reporting.jsonfactory.ComparisonReportWriter;
import de.jplag.reporting.reportobject.mapper.ClusteringResultMapper;
import de.jplag.reporting.reportobject.mapper.MetricMapper;
import de.jplag.reporting.reportobject.model.OverviewReport;
import de.jplag.reporting.reportobject.model.SubmissionFileIndex;
import de.jplag.reporting.reportobject.model.Version;
import de.jplag.reporting.reportobject.writer.JsonWriter;
import de.jplag.reporting.reportobject.writer.TextWriter;

/**
 * Factory class, responsible for converting a JPlagResult object to Overview and Comparison DTO classes and writing it
 * to the disk.
 */
public class ReportObjectFactory {
    private static final String DIRECTORY_ERROR = "Could not create directory {} for report viewer generation";

    private static final Logger logger = LoggerFactory.getLogger(ReportObjectFactory.class);

    private static final JsonWriter jsonFileWriter = new JsonWriter();
    public static final String OVERVIEW_FILE_NAME = "overview.json";

    public static final String README_FILE_NAME = "README.txt";
    public static final String README_CONTENT = "To view the results go to https://jplag.github.io/JPlag/ and drag the generated zip file onto the page.";

    public static final String SUBMISSIONS_FOLDER = "files";
    public static final String SUBMISSION_FILE_INDEX_FILE_NAME = "submissionFileIndex.json";
    public static final Version REPORT_VIEWER_VERSION = JPlag.JPLAG_VERSION;

    private Map<String, String> submissionNameToIdMap;
    private Function<Submission, String> submissionToIdFunction;
    private Map<String, Map<String, String>> submissionNameToNameToComparisonFileName;

    /**
     * Creates all necessary report viewer files, writes them to the disk as zip.
     * @param result The JPlagResult to be converted into a report.
     * @param path The Path to save the report to
     */
    public void createAndSaveReport(JPlagResult result, String path) {

        try {
            logger.info("Start writing report files...");
            createDirectory(path);
            buildSubmissionToIdMap(result);

            copySubmissionFilesToReport(path, result);

            writeComparisons(result, path);
            writeOverview(result, path);
            writeSubmissionIndexFile(result, path);
            writeReadMeFile(path);

            logger.info("Zipping report files...");
            zipAndDelete(path);
        } catch (IOException e) {
            logger.error(DIRECTORY_ERROR, e, path);
        }

    }

    private void zipAndDelete(String path) {
        boolean zipWasSuccessful = zipDirectory(path);
        if (zipWasSuccessful) {
            deleteDirectory(path);
        } else {
            logger.error("Could not zip results. The results are still available uncompressed at " + path);
        }
    }

    private void buildSubmissionToIdMap(JPlagResult result) {
        submissionNameToIdMap = buildSubmissionNameToIdMap(result);
        submissionToIdFunction = (Submission submission) -> submissionNameToIdMap.get(submission.getName());
    }

    private void copySubmissionFilesToReport(String path, JPlagResult result) {
        logger.info("Start copying submission files to the output directory...");
        List<JPlagComparison> comparisons = result.getComparisons(result.getOptions().maximumNumberOfComparisons());
        Set<Submission> submissions = getSubmissions(comparisons);
        File submissionsPath = createSubmissionsDirectory(path);
        if (submissionsPath == null) {
            return;
        }
        Language language = result.getOptions().language();
        for (Submission submission : submissions) {
            File directory = createSubmissionDirectory(path, submissionsPath, submission);
            File submissionRoot = submission.getRoot();
            if (directory == null) {
                continue;
            }
            for (File file : submission.getFiles()) {
                File fullPath = createSubmissionDirectory(path, submissionsPath, submission, file, submissionRoot);
                File fileToCopy = getFileToCopy(language, file);
                try {
                    if (fullPath != null) {
                        Files.copy(fileToCopy.toPath(), fullPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        throw new NullPointerException("Could not create file with full path");
                    }
                } catch (IOException e) {
                    logger.error("Could not save submission file " + fileToCopy, e);
                }
            }
        }
    }

    private File createSubmissionDirectory(String path, File submissionsPath, Submission submission, File file, File submissionRoot) {
        try {
            return createDirectory(submissionsPath.getPath(), submissionToIdFunction.apply(submission), file, submissionRoot);
        } catch (IOException e) {
            logger.error(DIRECTORY_ERROR, e, path);
            return null;
        }
    }

    private File createSubmissionDirectory(String path, File submissionsPath, Submission submission) {
        try {
            return createDirectory(submissionsPath.getPath(), submissionToIdFunction.apply(submission));
        } catch (IOException e) {
            logger.error(DIRECTORY_ERROR, e, path);
            return null;
        }
    }

    private File createSubmissionsDirectory(String path) {
        try {
            return createDirectory(path, SUBMISSIONS_FOLDER);
        } catch (IOException e) {
            logger.error(DIRECTORY_ERROR, e, path);
            return null;
        }
    }

    private File getFileToCopy(Language language, File file) {
        return language.useViewFiles() ? new File(file.getPath() + language.viewFileSuffix()) : file;
    }

    private void writeComparisons(JPlagResult result, String path) {
        ComparisonReportWriter comparisonReportWriter = new ComparisonReportWriter(submissionToIdFunction, jsonFileWriter);
        submissionNameToNameToComparisonFileName = comparisonReportWriter.writeComparisonReports(result, path);
    }

    private void writeOverview(JPlagResult result, String path) {

        List<File> folders = new ArrayList<>();
        folders.addAll(result.getOptions().submissionDirectories());
        folders.addAll(result.getOptions().oldSubmissionDirectories());

        String baseCodePath = result.getOptions().hasBaseCode() ? result.getOptions().baseCodeSubmissionDirectory().getName() : "";
        ClusteringResultMapper clusteringResultMapper = new ClusteringResultMapper(submissionToIdFunction);

        int totalComparisons = result.getAllComparisons().size();
        int numberOfMaximumComparisons = result.getOptions().maximumNumberOfComparisons();
        int shownComparisons = Math.min(totalComparisons, numberOfMaximumComparisons);
        int missingComparisons = totalComparisons > numberOfMaximumComparisons ? (totalComparisons - numberOfMaximumComparisons) : 0;
        logger.info("Total Comparisons: {}. Comparisons in Report: {}. Omitted Comparisons: {}.", totalComparisons, shownComparisons,
                missingComparisons);
        OverviewReport overviewReport = new OverviewReport(REPORT_VIEWER_VERSION, folders.stream().map(File::getPath).toList(), // submissionFolderPath
                baseCodePath, // baseCodeFolderPath
                result.getOptions().language().getName(), // language
                result.getOptions().fileSuffixes(), // fileExtensions
                submissionNameToIdMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey)), // submissionIds
                submissionNameToNameToComparisonFileName, // result.getOptions().getMinimumTokenMatch(),
                List.of(), // failedSubmissionNames
                result.getOptions().excludedFiles(), // excludedFiles
                result.getOptions().minimumTokenMatch(), // matchSensitivity
                getDate(),// dateOfExecution
                result.getDuration(), // executionTime
                MetricMapper.getDistributions(result), // distribution
                new MetricMapper(submissionToIdFunction).getTopComparisons(result),// topComparisons
                clusteringResultMapper.map(result), // clusters
                totalComparisons); // totalComparisons

        jsonFileWriter.writeFile(overviewReport, path, OVERVIEW_FILE_NAME);

    }

    private void writeReadMeFile(String path) {
        new TextWriter().writeFile(README_CONTENT, path, README_FILE_NAME);
    }

    private void writeSubmissionIndexFile(JPlagResult result, String path) {
        List<JPlagComparison> comparisons = result.getComparisons(result.getOptions().maximumNumberOfComparisons());
        Set<Submission> submissions = getSubmissions(comparisons);
        SubmissionFileIndex fileIndex = new SubmissionFileIndex(new HashMap<>());

        for (Submission submission : submissions) {
            List<String> filePaths = new LinkedList<>();
            for (File file : submission.getFiles()) {
                filePaths.add(FilePathUtil.getRelativeSubmissionPath(file, submission, submissionToIdFunction));
            }
            fileIndex.fileIndexes().put(submissionNameToIdMap.get(submission.getName()), filePaths);
        }
        jsonFileWriter.writeFile(fileIndex, path, SUBMISSION_FILE_INDEX_FILE_NAME);
    }

    private Set<Submission> getSubmissions(List<JPlagComparison> comparisons) {
        Set<Submission> submissions = comparisons.stream().map(JPlagComparison::firstSubmission).collect(Collectors.toSet());
        Set<Submission> secondSubmissions = comparisons.stream().map(JPlagComparison::secondSubmission).collect(Collectors.toSet());
        submissions.addAll(secondSubmissions);
        return submissions;
    }

    private String getDate() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yy");
        Date date = new Date();
        return dateFormat.format(date);
    }
}
