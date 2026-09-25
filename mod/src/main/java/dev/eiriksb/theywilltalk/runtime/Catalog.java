package dev.eiriksb.theywilltalk.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Everything the dashboard can download ({@code assets/theywilltalk/runtime/catalog.json}, compiled into the jar).
 * Every file is an https URL on GitHub or Hugging Face pinned to a SHA-256; nothing else can be downloaded.
 */
public record Catalog(List<Package> packages) {
    public static final String RESOURCE = "/assets/theywilltalk/runtime/catalog.json";

    private static final List<String> HOSTS = List.of("https://github.com/", "https://huggingface.co/");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * One file of a package: a download (optionally an archive to unpack) or a file shipped inside the mod jar.
     *
     * @param into folder (relative to the runtime folder) the file or the unpacked archive goes to
     * @param name file name for single files; defaults to the URL's last path segment
     */
    public record FileSpec(String url, String sha256, long size, String archive, String into, String name, int strip,
                           boolean flatten, List<String> include, List<String> exclude, String resource) {
        public boolean download() {
            return url != null;
        }

        public String fileName() {
            if (name != null) {
                return name;
            }
            String path = url != null ? url : resource;
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    public record Package(String id, String group, String name, String summary, String licence, String source,
                          List<String> tags, List<String> requires, List<String> check, String llmModel, String ttsEngine,
                          String unavailable, Map<String, List<FileSpec>> platforms) {
        public List<FileSpec> files(Platform platform) {
            List<FileSpec> files = platforms.get(platform.id);
            return files != null ? files : platforms.getOrDefault("any", List.of());
        }

        public boolean available(Platform platform) {
            return !files(platform).isEmpty();
        }

        public long downloadSize(Platform platform) {
            return files(platform).stream().filter(FileSpec::download).mapToLong(FileSpec::size).sum();
        }

        public boolean tagged(String tag) {
            return tags.contains(tag);
        }
    }

    public Optional<Package> get(String id) {
        return packages.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing " + RESOURCE);
            }
            return parse(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + RESOURCE, e);
        }
    }

    static Catalog parse(JsonObject root) {
        List<Package> packages = new ArrayList<>();
        for (JsonElement e : root.getAsJsonArray("packages")) {
            JsonObject p = e.getAsJsonObject();
            Map<String, List<FileSpec>> platforms = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> plat : p.getAsJsonObject("platforms").entrySet()) {
                List<FileSpec> files = new ArrayList<>();
                for (JsonElement f : plat.getValue().getAsJsonArray()) {
                    files.add(file(p.get("id").getAsString(), f.getAsJsonObject()));
                }
                platforms.put(plat.getKey(), List.copyOf(files));
            }
            packages.add(new Package(str(p, "id"), str(p, "group"), str(p, "name"), str(p, "summary"), str(p, "licence"),
                    str(p, "source"), strings(p, "tags"), strings(p, "requires"), strings(p, "check"), str(p, "llmModel"),
                    str(p, "ttsEngine"), str(p, "unavailable"), platforms));
        }
        return new Catalog(List.copyOf(packages));
    }

    private static FileSpec file(String pkg, JsonObject f) {
        FileSpec spec = new FileSpec(str(f, "url"), str(f, "sha256"), f.has("size") ? f.get("size").getAsLong() : 0,
                str(f, "archive"), str(f, "into"), str(f, "name"), f.has("strip") ? f.get("strip").getAsInt() : 0,
                f.has("flatten") && f.get("flatten").getAsBoolean(), strings(f, "include"), strings(f, "exclude"),
                str(f, "resource"));
        if (spec.into() == null || !safeRelative(spec.into()) || (spec.name() != null && !safeRelative(spec.name()))) {
            throw new IllegalStateException(pkg + ": bad target path");
        }
        if (spec.download()) {
            if (HOSTS.stream().noneMatch(spec.url()::startsWith) || spec.sha256() == null || !SHA256.matcher(spec.sha256()).matches()
                    || spec.size() <= 0) {
                throw new IllegalStateException(pkg + ": downloads must be pinned https GitHub/Hugging Face files: " + spec.url());
            }
        } else if (spec.resource() == null) {
            throw new IllegalStateException(pkg + ": a file needs a url or a resource");
        }
        return spec;
    }

    static boolean safeRelative(String path) {
        Path p = Path.of(path.replace("{platform}", "p").replace("{exe}", ""));
        return !p.isAbsolute() && !path.contains("..") && !path.startsWith("/") && !path.contains("\\");
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key)) {
            return List.of();
        }
        JsonArray a = o.getAsJsonArray(key);
        List<String> list = new ArrayList<>();
        a.forEach(x -> list.add(x.getAsString()));
        return List.copyOf(list);
    }
}
