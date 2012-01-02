
package net.sourceforge.filebot.media;


import static java.util.ResourceBundle.*;
import static java.util.regex.Pattern.*;
import static net.sourceforge.filebot.similarity.Normalization.*;
import static net.sourceforge.tuned.StringUtilities.*;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import net.sourceforge.filebot.web.CachedResource;
import net.sourceforge.filebot.web.Movie;
import net.sourceforge.tuned.ByteBufferInputStream;


public class ReleaseInfo {
	
	public String getVideoSource(File file) {
		// check parent and itself for group names
		return matchLast(getVideoSourcePattern(), getBundle(getClass().getName()).getString("pattern.video.source").split("[|]"), file.getParent(), file.getName());
	}
	
	
	public String getReleaseGroup(File file) throws IOException {
		// check parent and itself for group names
		return matchLast(getReleaseGroupPattern(false), releaseGroupResource.get(), file.getParent(), file.getName());
	}
	
	
	protected String matchLast(Pattern pattern, String[] standardValues, CharSequence... sequence) {
		String lastMatch = null;
		
		// match last occurrence
		for (CharSequence name : sequence) {
			if (name == null)
				continue;
			
			Matcher matcher = pattern.matcher(name);
			while (matcher.find()) {
				lastMatch = matcher.group();
			}
		}
		
		// prefer standard value over matched value
		if (lastMatch != null) {
			for (String standard : standardValues) {
				if (standard.equalsIgnoreCase(lastMatch)) {
					return standard;
				}
			}
		}
		
		return lastMatch;
	}
	
	
	public List<String> cleanRelease(Iterable<String> items, boolean strict) throws IOException {
		return clean(items, getReleaseGroupPattern(strict), getLanguageSuffixPattern(), getVideoSourcePattern(), getVideoFormatPattern(), getResolutionPattern(), getBlacklistPattern(false));
	}
	
	
	public String cleanRelease(String item, boolean strict) throws IOException {
		return clean(item, getReleaseGroupPattern(strict), getLanguageSuffixPattern(), getVideoSourcePattern(), getVideoFormatPattern(), getResolutionPattern(), getBlacklistPattern(false));
	}
	
	
	public List<String> clean(Iterable<String> items, Pattern... blacklisted) {
		List<String> cleanedItems = new ArrayList<String>();
		for (String it : items) {
			String cleanedItem = clean(it, blacklisted);
			if (cleanedItem.length() > 0) {
				cleanedItems.add(cleanedItem);
			}
		}
		
		return cleanedItems;
	}
	
	
	public String clean(String item, Pattern... blacklisted) {
		for (Pattern it : blacklisted) {
			item = it.matcher(item).replaceAll("");
		}
		
		return normalizePunctuation(item);
	}
	
	
	public Pattern getLanguageSuffixPattern() {
		Set<String> tokens = new TreeSet<String>();
		
		for (String code : Locale.getISOLanguages()) {
			Locale locale = new Locale(code);
			tokens.add(locale.getLanguage());
			tokens.add(locale.getISO3Language());
			for (Locale language : new HashSet<Locale>(Arrays.asList(Locale.ENGLISH, Locale.getDefault()))) {
				tokens.add(locale.getDisplayLanguage(language));
			}
		}
		
		// remove illegal tokens
		tokens.remove("");
		
		// .{language}[.srt]
		return compile("(?<=\\p{Punct})(" + join(tokens, "|") + ")(?=$)", CASE_INSENSITIVE | UNICODE_CASE | CANON_EQ);
	}
	
	
	public Pattern getResolutionPattern() {
		// match screen resolutions 640x480, 1280x720, etc
		return compile("(?<!\\p{Alnum})(\\d{4}|[6-9]\\d{2})x(\\d{4}|[4-9]\\d{2})(?!\\p{Alnum})");
	}
	
	
	public Pattern getVideoFormatPattern() {
		// pattern matching any video source name
		String pattern = getBundle(getClass().getName()).getString("pattern.video.format");
		return compile("(?<!\\p{Alnum})(" + pattern + ")(?!\\p{Alnum})", CASE_INSENSITIVE);
	}
	
	
	public Pattern getVideoSourcePattern() {
		// pattern matching any video source name
		String pattern = getBundle(getClass().getName()).getString("pattern.video.source");
		return compile("(?<!\\p{Alnum})(" + pattern + ")(?!\\p{Alnum})", CASE_INSENSITIVE);
	}
	
	
	public synchronized Pattern getReleaseGroupPattern(boolean strict) throws IOException {
		// pattern matching any release group name enclosed in separators
		return compile("(?<!\\p{Alnum})(" + join(releaseGroupResource.get(), "|") + ")(?!\\p{Alnum})", strict ? 0 : CASE_INSENSITIVE | UNICODE_CASE | CANON_EQ);
	}
	
	
	public synchronized Pattern getBlacklistPattern(boolean strict) throws IOException {
		// pattern matching any release group name enclosed in separators
		return compile("(?<!\\p{Alnum})(" + join(queryBlacklistResource.get(), "|") + ")(?!\\p{Alnum})", strict ? 0 : CASE_INSENSITIVE | UNICODE_CASE | CANON_EQ);
	}
	
	
	public synchronized Movie[] getMovieList() throws IOException {
		return movieListResource.get();
	}
	
	
	// fetch release group names online and try to update the data every other day
	protected final CachedResource<String[]> releaseGroupResource = new PatternResource(getBundle(getClass().getName()).getString("url.release-groups"));
	protected final CachedResource<String[]> queryBlacklistResource = new PatternResource(getBundle(getClass().getName()).getString("url.query-blacklist"));
	protected final CachedResource<Movie[]> movieListResource = new MovieResource(getBundle(getClass().getName()).getString("url.movie-list"));
	
	
	protected static class PatternResource extends CachedResource<String[]> {
		
		public PatternResource(String resource) {
			super(resource, String[].class, 24 * 60 * 60 * 1000); // 24h update interval
		}
		
		
		@Override
		public String[] process(ByteBuffer data) {
			return compile("\\n").split(Charset.forName("UTF-8").decode(data));
		}
	}
	
	
	protected static class MovieResource extends CachedResource<Movie[]> {
		
		public MovieResource(String resource) {
			super(resource, Movie[].class, 24 * 60 * 60 * 1000); // 24h update interval
		}
		
		
		@Override
		public Movie[] process(ByteBuffer data) throws IOException {
			Scanner scanner = new Scanner(new GZIPInputStream(new ByteBufferInputStream(data)), "UTF-8").useDelimiter("\t|\n");
			
			List<Movie> movies = new ArrayList<Movie>();
			while (scanner.hasNext()) {
				int imdbid = scanner.nextInt();
				String name = scanner.next();
				int year = scanner.nextInt();
				movies.add(new Movie(name, year, imdbid));
			}
			
			return movies.toArray(new Movie[0]);
		}
	}
	
}
