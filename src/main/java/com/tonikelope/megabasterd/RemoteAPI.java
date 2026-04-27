package com.tonikelope.megabasterd;
import static com.tonikelope.megabasterd.MiscTools.*;

import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static spark.Spark.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class RemoteAPI {
    private static final Logger LOG = Logger.getLogger(ChunkDownloader.class.getName());
    private final MainPanel _main_panel;
    private final DownloadManager _download_manager;
    private final javax.swing.JTree file_tree = new javax.swing.JTree();
    private final MegaAPI ma = new MegaAPI();
    public boolean enabled = false;
    public int port = 0;

    // Pending queue for async /start: raw URLs awaiting resolution
    private final ConcurrentLinkedQueue<PendingSubmission> _pending_queue = new ConcurrentLinkedQueue<>();

    // Track sourceUrl for folder-split downloads: per-file URL -> original folder URL
    private final ConcurrentHashMap<String, String> _source_url_map = new ConcurrentHashMap<>();

    private static class PendingSubmission {
        final String url;
        final String downloadPath;

        PendingSubmission(String url, String downloadPath) {
            this.url = url;
            this.downloadPath = downloadPath;
        }
    }

    public RemoteAPI(MainPanel main_panel) {
        _main_panel = main_panel;
        _download_manager = _main_panel.getDownload_manager();

        try{
            String enable_remote_api_val = DBTools.selectSettingValue("enable_remote_api");
            if (enable_remote_api_val != null) {
                enabled = enable_remote_api_val.equals("yes");
            } else {
                // Default to enabled on first run and persist the setting
                enabled = true;
                DBTools.insertSettingValue("enable_remote_api", "yes");
            }

            String remote_api_p = DBTools.selectSettingValue("remote_api_port");
            if (remote_api_p == null) {
                remote_api_p = String.valueOf(MainPanel.DEFAULT_REMOTE_API_PORT);
                DBTools.insertSettingValue("remote_api_port", remote_api_p);
            }
            port = Integer.parseInt(remote_api_p);

            // do not start if not enabled or port is 0
            if(!enabled || port == 0) return;
            port(port); // Set the port number
        } catch (Exception ex){
            LOG.log(Level.SEVERE, "Unable to start remote api server.", ex.getMessage());
        }

        // Start background thread to process pending submissions
        Thread processorThread = new Thread(this::_processPendingQueue, "RemoteAPI-PendingProcessor");
        processorThread.setDaemon(true);
        processorThread.start();

        Gson gson = new Gson();

        // CORS headers for local development
        before((req, res) -> {
            res.header("Access-Control-Allow-Origin", "*");
            res.header("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type");
        });

        options("/*", (req, res) -> {
            res.status(200);
            return "";
        });

        get("/status", (req, res) -> {
            res.type("application/json");

            // system status
            Map<String, Object> status = new HashMap<>();
            status.put("size", _download_manager.get_total_size());
            status.put("loaded", _download_manager.get_total_progress());;
            status.put("running", _download_manager.getTransference_running_list().size() > 0 && !_download_manager.isPaused_all());

            // get downloads
            ArrayList<Map<String, Object>> downloads = new ArrayList<>();

            // Include pending entries
            for (PendingSubmission pending : _pending_queue) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("url", pending.url);
                entry.put("name", null);
                entry.put("path", "");
                entry.put("bytesTotal", 0);
                entry.put("bytesLoaded", 0);
                entry.put("speed", 0);
                entry.put("status", "Pending");
                entry.put("finished", false);
                entry.put("error", null);
                entry.put("workers", 0);
                entry.put("error509Count", 0);
                downloads.add(entry);
            }

            // Include active/completed downloads
            for (Download dl : getDownloads()) {
                String dlStatus = dl.getView().getStatus_label().getText();
                boolean finished = false;

                switch(dlStatus) {
                    case "File successfully downloaded!":
                    case "File successfully downloaded! (Integrity check PASSED)":
                    case "File successfully downloaded! (but integrity check CANCELED)":
                        finished = true;
                        break;
                }

                // Check if  Bandwidth Limit is Exceeded
                int error509Count = 0;
                ArrayList<ChunkDownloader> workers = dl.getChunkworkers();
                for (ChunkDownloader chunkDownloader : dl.getChunkworkers()) {
                    if(chunkDownloader.isBandwidthExceeded()){
                        error509Count ++;
                    }
                }
                if(error509Count > 0){
                    dlStatus = "509 Bandwidth Limit Exceeded";
                }

                if (dl.isStatusError()){
                    dlStatus = "Error";
                }

                Map<String, Object> entry = createDownloadStatus(dl.getUrl(), dl.getFile_name(), getDownloadPath(dl),
                        dl.getFile_size(), dl.getProgress(), dl.getFile_size() == dl.getProgress() ? 0 : dl.getSpeed(),
                        dlStatus, dl.getStatus_error(), finished, workers.size(), error509Count);

                // Add sourceUrl for folder-split downloads
                String sourceUrl = _source_url_map.get(dl.getUrl());
                if (sourceUrl != null) {
                    entry.put("sourceUrl", sourceUrl);
                }

                downloads.add(entry);
            }
            status.put("downloads", downloads);

            ObjectMapper mapper = new ObjectMapper();
            return mapper.writeValueAsString(status);
        });

        post("/clear509", (req, res) -> {
            res.type("application/json");

            for (Download dl : getDownloads()) {
                for (ChunkDownloader chunkDownloader : dl.getChunkworkers()) {
                    if(chunkDownloader.isBandwidthExceeded()) {
                        chunkDownloader.forceRestartAfter509();
                        chunkDownloader.run();
                    }
                }
            }

            return "{\"messsage\": \"Cleared 509 Errors.\"}";
        });

        post("/pause", (req, res) -> {
            res.type("application/json");

            _download_manager.pauseAll();
            return "{\"messsage\": \"Paused all downloads\"}";
        });

        post("/resume", (req, res) -> {
            res.type("application/json");

            _download_manager.resumeAll();
            return "{\"messsage\": \"Resumed all downloads\"}";
        });

        post("/start", (req, res) -> {
            try {

                res.type("application/json");

                // Parse the JSON body
                JsonObject jsonBody = JsonParser.parseString(req.body()).getAsJsonObject();
                if (!jsonBody.has("urls")) {
                    res.status(400); // Bad Request
                    return gson.toJson(new ErrorResponse("Missing required parameters."));
                }

                String linksText = jsonBody.get("urls").getAsString();
                // Extract raw URLs without resolving folders
                List<String> rawUrls = extractRawUrls(linksText);

                if (rawUrls.isEmpty()) {
                    res.status(400); // Bad Request
                    return gson.toJson(new ErrorResponse("No mega links found."));
                }

                String downloadPath = _main_panel.getDefault_download_path();
                if(jsonBody.has("dest")){
                    downloadPath = Paths.get(_main_panel.getDefault_download_path(), jsonBody.get("dest").getAsString()).toString();
                    File path = new File(downloadPath);
                    if(!path.exists()) path.mkdirs();
                }

                // Queue URLs for async processing
                for (String url : rawUrls) {
                    _pending_queue.add(new PendingSubmission(url, downloadPath));
                }

                LOG.log(Level.INFO, "Queued {0} URLs for async processing", rawUrls.size());

                ObjectMapper mapper = new ObjectMapper();
                Map<String, Object> response = new HashMap<>();
                response.put("message", rawUrls.size() + " links queued");
                response.put("urls", rawUrls);
                return mapper.writeValueAsString(response);
            } catch (Exception ex){
                res.status(500);
                return "{\"message\": \""+ex.toString()+"\"}";
            }
        });

        post("/stop", (req, res) -> {
            try {
                res.type("application/json");

                // Parse the JSON body
                JsonObject jsonBody = JsonParser.parseString(req.body()).getAsJsonObject();
                if (!jsonBody.has("url")) {
                    res.status(400); // Bad Request
                    return gson.toJson(new ErrorResponse("Missing required parameters."));
                }

                String downloadUrl = jsonBody.get("url").getAsString();

                boolean deleteFiles = false;
                if(jsonBody.has("delete")) deleteFiles = jsonBody.get("delete").getAsBoolean();

                // Also check pending queue — remove if still pending
                boolean removedPending = _pending_queue.removeIf(p -> Objects.equals(p.url, downloadUrl));
                if (removedPending) {
                    return "{\"message\": \"Removed pending download\"}";
                }

                Download download = findDownloadByURL(downloadUrl);
                if(download == null) {
                    res.status(404);
                    return "{\"message\": \"Download not found.\"}";
                } else {
                    // stop download
                    download.getView().getPause_button().doClick();
                    download.getView().getKeep_temp_checkbox().setSelected(!deleteFiles);
                    download.getView().getStop_button().doClick();

                    // wait for clear but then remove download from list with 30-second timeout
                    long startTime = System.currentTimeMillis();
                    while (!download.getView().getClose_button().isVisible()) {
                        if (System.currentTimeMillis() - startTime > 30000) {
                            break; // Timeout after 30 seconds
                        }
                        Thread.sleep(100);
                    }

                    // If the close button is visible, click it
                    if (download.getView().getClose_button().isVisible()) {
                        download.getView().getClose_button().doClick();

                        // Clean up sourceUrl mapping
                        _source_url_map.remove(download.getUrl());

                        // delete files if delete = true, is not default and folder is empty
                        Path downloadPath = Paths.get(download.getDownload_path());
                        boolean deleted = false;
                        if(deleteFiles && !downloadPath.toString().equals(_main_panel.getDefault_download_path())) {
                            String downloadFolder = getDownloadPath(download);

                            // delete file
                            Path filePath = Paths.get(_main_panel.getDefault_download_path(), downloadFolder, download.getFile_name());
                            File file = new File(filePath.toString());
                            if(file.exists()) file.delete();

                            // delete folders if empty
                            ArrayList<String> pathsToCheck = new ArrayList<>();
                            Path currentPath = Paths.get(_main_panel.getDefault_download_path());
                            for (String folder : downloadFolder.split("/")) {
                                currentPath = Paths.get(currentPath.toString(), folder);
                                pathsToCheck.add(currentPath.toString());
                            }

                            Collections.reverse(pathsToCheck);
                            for (String folder : pathsToCheck) {
                                Path folderPath = Paths.get(folder);
                                if (isFolderEmpty(folderPath)) {
                                    Files.delete(folderPath);
                                    deleted = true;
                                }
                            }
                        }

                        if(deleted){
                            return "{\"message\": \"Removed download and deleted\"}";
                        }
                        return "{\"message\": \"Removed download\"}";
                    } else {
                        return "{\"message\": \"Failed to remove download within timeout.\"}";
                    }
                }
            } catch (Exception ex){
                res.status(500);
                return "{\"message\": \""+ex.toString()+"\"}";
            }
        });


        post("/rename", (req, res) -> {
            try {
                res.type("application/json");

                // Parse the JSON body
                JsonObject jsonBody = JsonParser.parseString(req.body()).getAsJsonObject();
                if (!jsonBody.has("url") || !jsonBody.has("newName")) {
                    res.status(400); // Bad Request
                    return gson.toJson(new ErrorResponse("Missing required parameters."));
                }

                String downloadUrl = jsonBody.get("url").getAsString();
                String newName = jsonBody.get("newName").getAsString();
                Download download = findDownloadByURL(downloadUrl);
                if(download == null) {
                    res.status(404);
                    return "{\"message\": \"Download not found.\"}";
                }

                String statusText = download.getView().getStatus_label().getText();
                if(!statusText.contains("File successfully downloaded!")) {
                    res.status(500);
                    return "{\"message\": \"Download is not completed.\"}";
                }

                boolean renamed = download.setFile_name(newName);
                if(renamed) {
                    DBTools.updateDownloadFilename(newName, downloadUrl);
                    return "{\"message\": \"Renamed download\"}";
                } else {
                    res.status(400);
                    return "{\"message\": \"Unable to rename download\"}";
                }

            } catch (Exception ex){
                res.status(500);
                return "{\"message\": \""+ex.toString()+"\"}";
            }
        });

        // Catch-all for 404 Not Found
        notFound((req, res) -> {
            res.type("text/plain");
            return "404 Not Found";
        });
    }

    /**
     * Background thread: processes the pending queue — resolves folder links,
     * creates Download objects, and adds them to the download manager.
     */
    private void _processPendingQueue() {
        while (true) {
            try {
                PendingSubmission pending = _pending_queue.peek();
                if (pending == null) {
                    Thread.sleep(500);
                    continue;
                }

                LOG.log(Level.INFO, "Processing pending URL: {0}", pending.url);

                // Resolve the URL (may be a single file or a folder)
                Set<String> resolvedUrls = parseUrls(pending.url);

                boolean isFolderSplit = resolvedUrls.size() > 1 ||
                    (resolvedUrls.size() == 1 && !resolvedUrls.iterator().next().equals(pending.url));

                // Create downloads for each resolved URL
                for (String url : resolvedUrls) {
                    Download download = new Download(
                            _main_panel, ma, url, pending.downloadPath,
                            null, null, null, null, null, _main_panel.isUse_slots_down(),
                            false, _main_panel.isUse_custom_chunks_dir() ? _main_panel.getCustom_chunks_dir() : null, false);
                    _download_manager.getTransference_provision_queue().add(download);
                    _download_manager.secureNotify();

                    // Track sourceUrl for folder-split downloads
                    if (isFolderSplit) {
                        _source_url_map.put(url, pending.url);
                    }
                }

                LOG.log(Level.INFO, "Processed pending URL: {0} -> {1} downloads", new Object[]{pending.url, resolvedUrls.size()});

                // Remove from queue after successful processing
                _pending_queue.poll();

            } catch (Exception ex) {
                LOG.log(Level.SEVERE, "Error processing pending URL", ex);
                // Remove the failing entry to avoid infinite retry
                _pending_queue.poll();
            }
        }
    }

    static class ErrorResponse {
        String message;

        public ErrorResponse(String message) {
            this.message = message;
        }
    }

    private Download findDownloadByURL(String url)
    {
        Download download = null;
        for (Download dl : getDownloads()) {
            if (Objects.equals(dl.getUrl(), url)) {
                download = dl;
                break;
            }
        }

        return download;
    }

    private String getDownloadPath(Download dl)
    {
        Path defaultDownloadPath = Paths.get(_main_panel.getDefault_download_path());
        Path dlFullPath = Paths.get(dl.getDownload_path());

        ArrayList<String> dlPathList = new ArrayList<>();
        for (int i = 0; i < dlFullPath.getNameCount(); i++) {
            if(i > defaultDownloadPath.getNameCount()-1){
                dlPathList.add(dlFullPath.getName(i).toString());
            }
        }

        return String.join("/", dlPathList);
    }

    public static boolean isFolderEmpty(Path folderPath) {
        try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(folderPath)) {
            return !dirStream.iterator().hasNext(); // If no entries exist, folder is empty
        } catch (IOException e) {
            return false; // Return false in case of an error
        }
    }

    /**
     * Extract raw mega.nz URLs from text without resolving folder links.
     * Used by /start to quickly identify URLs for queuing.
     */
    private List<String> extractRawUrls(String linksText) {
        String link_data = MiscTools.extractMegaLinksFromString(linksText);
        List<String> urls = new ArrayList<>();

        // Match file links
        Set<String> fileLinks = new HashSet(findAllRegex("(?:https?|mega)://[^\r\n]+(#[^\r\n!]*?)?![^\r\n!]+![^\\?\r\n/]+", link_data, 0));
        urls.addAll(fileLinks);

        // Match folder links (https://mega.nz/folder/id#key or https://mega.nz/#F!id!key)
        Set<String> folderLinks = new HashSet(findAllRegex("https?://mega\\.nz/folder/[^\\s]+", link_data, 0));
        Set<String> legacyFolderLinks = new HashSet(findAllRegex("https?://mega\\.nz/#F![^\\s]+", link_data, 0));

        // Add folder links that aren't already captured as file links
        for (String fl : folderLinks) {
            if (!urls.contains(fl)) urls.add(fl);
        }
        for (String fl : legacyFolderLinks) {
            if (!urls.contains(fl)) urls.add(fl);
        }

        return urls;
    }

    private Set<String> parseUrls(String linksText){
        String link_data = MiscTools.extractMegaLinksFromString(linksText);
        Set<String> urls = new HashSet(findAllRegex("(?:https?|mega)://[^\r\n]+(#[^\r\n!]*?)?![^\r\n!]+![^\\?\r\n/]+", link_data, 0));


        // remove folder links (not supported yet)
        if (!urls.isEmpty()) {
            Set<String> folder_file_links = new HashSet(findAllRegex("(?:https?|mega)://[^\r\n]+#F\\*[^\r\n!]*?![^\r\n!]+![^\\?\r\n/]+", link_data, 0));
            if (!folder_file_links.isEmpty()) {
                ArrayList<String> nlinks = ma.GENERATE_N_LINKS(folder_file_links, true);
                urls.removeAll(folder_file_links);
                urls.addAll(nlinks);
            }
        }

        // get links for folder files
        for (String url : urls) {
            if (findFirstRegex("#F!", url, 0) != null) {
                urls.remove(url);

                FolderLinkDialog fdialog = new FolderLinkDialog(_main_panel.getView(), false, url);
                while (!fdialog.isReady) { }
                for (var link : fdialog.getDownload_links()) {
                    urls.add(link.get("url").toString());
                }
                fdialog.dispose();
            }
        }

        return urls;
    }

    private ArrayList<Download> getDownloads(){
        ArrayList<Download> downloads = new ArrayList<>();

        // gets all the download components from the list
        Component[] components = _download_manager.getScroll_panel().getComponents();
        for (Component component : components) {
            try {
                if (component instanceof DownloadView) {
                    downloads.add(((DownloadView) component).getDownload());
                }
            } catch (Exception ignored) {}
        }

        return downloads;
    }

    // Helper method to create a DownloadStatus JSONObject
    public static Map<String, Object> createDownloadStatus(String url, String name, String path, long size, long bytesLoaded, long speed, String status, String error, Boolean finished, int workers, int error509Count) {
        Map<String, Object> downloadStatus = new HashMap<>();
        downloadStatus.put("url", url);
        downloadStatus.put("name", name);
        downloadStatus.put("path", path);
        downloadStatus.put("bytesTotal", size);
        downloadStatus.put("bytesLoaded", finished ? size : bytesLoaded);
        downloadStatus.put("speed", speed);
        downloadStatus.put("status", status);
        downloadStatus.put("finished", finished);
        downloadStatus.put("error", error);
        downloadStatus.put("workers", workers);
        downloadStatus.put("error509Count", error509Count);
        return downloadStatus;
    }
}
