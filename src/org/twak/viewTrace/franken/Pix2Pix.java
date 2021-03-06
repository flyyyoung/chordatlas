package org.twak.viewTrace.franken;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.RescaleOp;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.vecmath.Point2d;

import org.apache.commons.io.FileUtils;
import org.twak.tweed.Tweed;
import org.twak.tweed.TweedSettings;
import org.twak.utils.Imagez;
import org.twak.utils.collections.Loop;
import org.twak.utils.geom.DRectangle;
import org.twak.viewTrace.facades.CMPLabel;
import org.twak.viewTrace.facades.FRect;
import org.twak.viewTrace.facades.GreebleHelper;
import org.twak.viewTrace.facades.MiniFacade;
import org.twak.viewTrace.facades.NormSpecGen;

/**
 * Utilties to synthesize facade using Pix2Pix network
 */

public class Pix2Pix {

	String netName;
	int resolution;
	Map<Object, String> inputs = new HashMap<>();


	public Pix2Pix (NetInfo ni) {
		this.netName = ni.name;//netName;
		this.resolution = ni.resolution;
	}
	
	public Pix2Pix (String netName, int res) {
		this.netName = netName;
		this.resolution = res;
	}
	
	public interface JobResult {
		public void finished ( Map<Object, File>  results);
	}
	
	public interface EResult {
		public void finished ( Map<Object, double[]>  results);
	}
	
	public static class Job implements JobResult {
		
		JobResult finished;
		public String name;
		boolean encode= false;
		public EResult eFinished;
		
		public Job () {
			this.name = System.nanoTime() +"--"+ Math.random();
			this.finished = this;
		}
		public Job (JobResult finished) {
			this();
			this.finished = finished;
		}
		
		public Job (JobResult finished, boolean encode) {
			this (finished);
			this.encode = encode;
		}
		
		public Job (EResult finished) {
			this.name = System.nanoTime() +"--"+ Math.random();
			this.eFinished = finished;
			this.encode = true;
		}

		@Override
		public void finished( Map<Object, File> results ) {
			finished.finished( results );
		}
	}
	

	static final String [] inputMapNames = new String[] {"", "_empty", "_mlabels" }; 
	
	public void addInput( BufferedImage input, BufferedImage empty,
			BufferedImage mLabels, Object key, double[] styleZ, Double scale ) {
		
		BufferedImage[] bis = new BufferedImage[] {input, empty, mLabels};
		String name = UUID.randomUUID() +  ( scale == null? "" : ("@" + scale ));
		
		for ( int i = 0; i < bis.length; i++ ) {
			try {

				BufferedImage bi = bis[ i ];
				
				if ( bi == null )
					continue;

				File dir = new File( TweedSettings.settings.bikeGanRoot + "/input/" + netName + inputMapNames[i] + "/val/" );
				dir.mkdirs();
				String nameWithZ = name + zAsString( styleZ );

				ImageIO.write( bi, "png", new File( dir, nameWithZ + ".png" ) );
				inputs.put( key, nameWithZ );

			} catch ( IOException e ) {
				e.printStackTrace();
			}
		}
	}
	
	
	public void submit( Job job ) {
		
		String network = netName;
		
		if (job.encode)
			network = network+"_e";
			
		File go     = new File( TweedSettings.settings.bikeGanRoot + "/input/"  + network + "/val/go" );
		File outDir = new File( TweedSettings.settings.bikeGanRoot + "/output/" + network +"/" + job.name );
		
		if (inputs.isEmpty()) {
			outDir.mkdirs();
			finished( job, outDir );
			return;
		}
		
		try {
			FileWriter  fos = new FileWriter( go );
			fos.write( job.name );
			fos.close();
			
			
		} catch ( Throwable e1 ) {
			e1.printStackTrace();
		}

		long startTime = System.currentTimeMillis();

		do {
			try {
				Thread.sleep( 50 );
				
			} catch ( InterruptedException e ) {
				e.printStackTrace();
			}
		} while ( go.exists() && System.currentTimeMillis() - startTime < 1e4 );

		startTime = System.currentTimeMillis();
	
		if (!go.exists()) 
		do {

			try {
				Thread.sleep( 50 );
			} catch ( InterruptedException e ) {
				e.printStackTrace();
			}

			if ( outDir.exists() ) {
				
				finished( job, outDir );
				return;
			}
			
		} while ( System.currentTimeMillis() - startTime < 6000 );
		
		System.out.println( "timeout trying to get bikeGAN result "+ job.name +". \nis bikeGAN running? \nhas bikeGAN root been set correctly in the settings menu? (" + TweedSettings.settings.bikeGanRoot );
	}

	private void finished( Job job, File outDir ) {
//		System.out.println( "processing "+job.name );
		
		if (job.encode) {
			
			Map<Object, double[]> done =new HashMap<>();
			
			for (File f : outDir.listFiles())  {
				
				for (Map.Entry<Object, String> e : inputs.entrySet())
					if (f.getName().startsWith( e.getValue() )) {
						String[] ls = f.getName().replaceAll( e.getValue()+"@", "" ).split("_");
						
						double[] out = new double[ls.length];
						for (int i = 0; i< ls.length; i++)
							out[i] = Double.parseDouble( ls[i] );
						done.put( e.getKey(), out );
					}
			}
			
			job.eFinished.finished( done );
		}
		else {
			Map<Object, File> done =new HashMap<>();
			for (Map.Entry<Object, String> e : inputs.entrySet())
				done.put( e.getKey(), new File (outDir, e.getValue()+".png") );
		
			job.finished.finished( done );
		}
		
		try {
			FileUtils.deleteDirectory( outDir );
		} catch ( IOException e ) {
			e.printStackTrace();
		}
	}
	
	public BufferedImage encode(File f, double[] values, Runnable update ) {

		try {
			
			BufferedImage bi = ImageIO.read( f );
			BufferedImage orig = bi;
			bi = Imagez.scaleSquare( bi, resolution );

			File dir = new File( TweedSettings.settings.bikeGanRoot + "input/" + netName + "_e/val/" );
			dir.mkdirs();

			addEInput( bi, new Object() );
			
			submit( new Job( new EResult() {
				
				@Override
				public void finished( Map<Object, double[]> results ) {
					
					o:
					for ( Map.Entry<Object, double[]> e : results.entrySet() ) 
						for ( int i = 0; i < e.getValue().length; i++ ) 
							values[ i ] = e.getValue()[i];
				
					update.run();
				}
			} ) );

			return orig;
			
		} catch ( Throwable e ) {
			e.printStackTrace();
		}
		return null;
	}

	public static String importTexture( File texture, int specular, Map<Color, Color> specLookup, 
			DRectangle crop, RescaleOp rgbRescale, BufferedImage[] output ) throws IOException {
		
		new File( Tweed.SCRATCH ).mkdirs();

		
		String dest = "missing";
		File labelFile = new File( texture.getParentFile(), texture.getName() + "_label" );
		
		if ( texture.exists() && texture.length() > 0 ) {
			
			dest =  "scratch/" + UUID.randomUUID();

			BufferedImage rgb = ImageIO.read( texture );

			if (rgbRescale != null)
				rgb = rgbRescale.filter( rgb, null );
			
			BufferedImage labels = ImageIO.read( labelFile ); 

			if (crop != null) {

				ImageIO.write( rgb    , "png", new File( Tweed.DATA + "/" + ( dest + "_nocrop.png" ) ) );
				
				rgb = scaleToFill ( rgb, crop );
				labels = scaleToFill ( labels, crop );
			}
			
			
			NormSpecGen ns = new NormSpecGen( rgb, labels, specLookup );
			
			if (specular >= 0) {
				Graphics2D g = ns.spec.createGraphics();
				g.setColor( new Color (specular, specular, specular) );
				g.fillRect( 0, 0, ns.spec.getWidth(), ns.spec.getHeight() );
				g.dispose();
			}
			
			output[0] = rgb;
			output[1] = ns.spec;
			output[2] = ns.norm;

			ImageIO.write( rgb    , "png", new File( Tweed.DATA + "/" + ( dest + ".png" ) ) );
			ImageIO.write( ns.norm, "png", new File( Tweed.DATA + "/" + ( dest + "_norm.png" ) ) );
			ImageIO.write( ns.spec, "png", new File( Tweed.DATA + "/" + ( dest + "_spec.png" ) ) );
			ImageIO.write( labels , "png", new File( Tweed.DATA + "/" + ( dest + "_lab.png" ) ) );
			
		}
		
		texture.delete();
		labelFile.delete();
		
		return dest + ".png";
	}
	
	public static BufferedImage scaleToFill( BufferedImage rgb, DRectangle crop ) {
		
		BufferedImage out = new BufferedImage (rgb.getWidth(), rgb.getHeight(), rgb.getType() );
		
		Graphics2D g = out.createGraphics();
		g.setRenderingHint( RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR );
		g.setRenderingHint( RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY );
		
		int cropy = (int) (256 - crop.height - crop.y);
		g.drawImage( rgb, 0, 0, rgb.getWidth(), rgb.getHeight(), 
				(int) crop.x, cropy, (int) (crop.x + crop.width), (int) (cropy + crop.height ),
				null );
		g.dispose();
		
		return out;
	}

	public static DRectangle findBounds( MiniFacade toEdit, boolean includeRoof ) {
		
		FacadeTexApp fta = toEdit.facadeTexApp;
		
		if ( fta.postState == null ) 
			return toEdit.getAsRect();
		
		else if ( includeRoof ) {
			DRectangle out =  GreebleHelper.findRect( fta.postState.wallFaces, fta.postState.roofFaces );
			if (out != null )
				return out;
		}
		else {
			DRectangle out =  GreebleHelper.findRect( fta.postState.wallFaces );
			if (out != null )
				return out;
		}

		if ( fta.postState == null || fta.postState.outerWallRect == null ) 
			return toEdit.getAsRect();
		else
			return fta.postState.outerWallRect;
	}
	
	public static void drawFacadeBoundary( Graphics2D g, MiniFacade mf, DRectangle mini, DRectangle mask, boolean drawRoofs ) {
		
		FacadeTexApp fta = mf.facadeTexApp;
		
		if ( fta.postState == null ) {
			Pix2Pix.cmpRects( mf, g, mask, mini, Color.blue, Collections.singletonList( new FRect( mini, mf ) ), 256 );
		} else {
			g.setColor( Color.blue );
			
			for ( Loop<? extends Point2d> l : fta.postState.wallFaces )
				g.fill( Pix2Pix.toPoly( mf, mask, mini, l ) );
			
			if (drawRoofs)
				for ( Loop<? extends Point2d> l : fta.postState.roofFaces )
					g.fill( Pix2Pix.toPoly( mf, mask, mini, l ) );

			g.setColor( CMPLabel.Background.rgb );
			for ( Loop<Point2d> l : fta.postState.occluders ) 
				g.fill( Pix2Pix.toPoly( mf, mask, mini, l ) );
		}
	}

	public void addEInput ( BufferedImage bi, Object key ) {
		try {
			
			String name = UUID.randomUUID() +"";
			
			File dir = new File( TweedSettings.settings.bikeGanRoot + "/input/" + netName + "_e/val/" );
			dir.mkdirs();
			inputs.put( key, name );
			
			ImageIO.write( bi, "png", new File( dir, name + ".png" ) );
			
		} catch ( IOException e ) {
			e.printStackTrace();
		}
	}
	
	public static String zAsString(double[] z) {
		
		if (z == null)
			return "noz";
		
		String zs = "";
		for ( double d : z )
			zs += "_" + d;
		return zs;
	}
	
	public static Polygon toPoly( MiniFacade toEdit, DRectangle bounds, DRectangle mini, Loop<? extends Point2d> loop ) {
		Polygon p = new Polygon();

		for ( Point2d pt : loop ) {
			Point2d p2 = bounds.transform( mini.normalize( pt ) );
			p.addPoint( (int) p2.x, (int) ( -p2.y + 256 ) );
		}
		return p;
	}
	

	public static void cmpRects( MiniFacade mf, Graphics2D g, DRectangle bounds, DRectangle mini, Color col, List<FRect> rects, int heightForYFlip ) {

		FacadeTexApp fta = mf.facadeTexApp;

		//		double scale = 1/ ( mini.width < mini.height ? mini.height : mini.width );
		//		
		//		mini = new DRectangle(mini);
		//		mini.scale( scale );
		//		
		//		mini.x = (1-mini.width) / 2;
		//		mini.y = (1-mini.height) / 2;

		g.setColor( col );
		
		for ( FRect r : rects ) {

//			if ( fta.postState == null || fta.postState.generatedWindows.contains( r ) ) 
			{
				
				DRectangle w = bounds.transform( mini.normalize( r ) );

				w.y = heightForYFlip - w.y - w.height;

				g.fillRect( (int) w.x, (int) w.y, (int) w.width, (int) w.height );
			}
		}
	}
}
