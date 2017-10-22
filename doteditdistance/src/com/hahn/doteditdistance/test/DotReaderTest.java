package com.hahn.doteditdistance.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import com.hahn.doteditdistance.DotReader;

import no.roek.nlpged.graph.Graph;

public class DotReaderTest {
	
	public static void main(String[] args) {
		InputStream is = null;
		try {
			is = new FileInputStream(new File(args[0]));
			Graph g = DotReader.read("g", is);
			
			System.out.println(g.toString());
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} finally {
			if (is != null)
				try {
					is.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
		}
	}

}
