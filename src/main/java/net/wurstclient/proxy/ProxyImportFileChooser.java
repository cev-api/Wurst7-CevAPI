/*
 * Copyright (c) 2014-2026 Wurst-Imperium and contributors.
 *
 * This source code is subject to the terms of the GNU General Public
 * License, version 3. If a copy of the GPL was not distributed with this
 * file, You can obtain one at: https://www.gnu.org/licenses/gpl-3.0.txt
 */
package net.wurstclient.proxy;

import java.awt.Component;
import java.awt.HeadlessException;
import java.io.File;

import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.wurstclient.util.SwingUtils;

public final class ProxyImportFileChooser extends JFileChooser
{
	public static void main(String[] args)
	{
		SwingUtils.setLookAndFeel();
		JFileChooser fileChooser =
			new ProxyImportFileChooser(new File(args[0]));
		fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
		fileChooser.setAcceptAllFileFilterUsed(false);
		fileChooser.addChoosableFileFilter(
			new FileNameExtensionFilter("Text file (proxy list)", "txt"));
		
		if(fileChooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION)
			return;
		
		System.out.println(fileChooser.getSelectedFile().getAbsolutePath());
	}
	
	private ProxyImportFileChooser(File currentDirectory)
	{
		super(currentDirectory);
	}
	
	@Override
	protected JDialog createDialog(Component parent) throws HeadlessException
	{
		JDialog dialog = super.createDialog(parent);
		dialog.setAlwaysOnTop(true);
		return dialog;
	}
}
