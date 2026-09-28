package pcgen.persistence;

import java.io.File;
import java.io.FilenameFilter;
import java.net.URI;
import java.util.List;

import pcgen.util.Logging;

import org.apache.commons.lang3.StringUtils;

public class RecursiveFileFinder
{

	private final FilenameFilter pccFileFilter;

	public RecursiveFileFinder()
	{
		pccFileFilter = (parentDir, fileName) -> StringUtils.endsWithIgnoreCase(fileName, ".pcc")
				|| new File(parentDir, fileName).isDirectory();
	}

	/**
	 * Recursively looks inside a given directory for PCC files
	 * and adds them to the campaignFiles list.
	 *
	 * @param aDirectory    The directory to search.
	 * @param campaignFiles
	 */
	public void findFiles(final File aDirectory, List<URI> campaignFiles)
	{
		if (!aDirectory.isDirectory())
		{
			return;
		}
		_findFiles(aDirectory, campaignFiles);
	}

	private void _findFiles(File aDirectory, List<URI> campaignFiles)
	{
		File[] files = aDirectory.listFiles(pccFileFilter);
		if (files == null)
		{
			// Unreadable directory (permissions, a dangling link, an unmounted drive):
			// skip it rather than aborting the scan of every other data directory.
			Logging.errorPrint("Could not read data directory " + aDirectory);
			return;
		}
		for (final File file : files)
		{
			if (file.isDirectory())
			{
				_findFiles(file, campaignFiles);
				continue;
			}
			campaignFiles.add(file.toURI());
		}
	}
}
