package com.autodonut.client;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.filter.RegexFilter;

import com.autodonut.AutoDonut;

/**
 * Hides the harmless shader-linking notes macOS's OpenGL driver makes Minecraft log
 * ("Info log when linking program ... Output of vertex shader ... not read by fragment shader").
 * Only those messages are dropped; everything else is logged as usual.
 */
public final class LogFilter {
	private static final String PATTERN = "(?s).*(Info log when linking program|not read by fragment shader).*";

	private LogFilter() {
	}

	public static void install() {
		try {
			LoggerContext context = (LoggerContext) LogManager.getContext(false);
			Filter filter = RegexFilter.createFilter(PATTERN, null, false, Filter.Result.DENY, Filter.Result.NEUTRAL);
			context.getConfiguration().addFilter(filter);
			context.updateLoggers();
		} catch (Exception | LinkageError e) {
			AutoDonut.LOGGER.debug("Couldn't install the shader log filter", e);
		}
	}
}
