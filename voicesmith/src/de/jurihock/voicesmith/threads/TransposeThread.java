/*******************************************************************************
 * src/de/jurihock/voicesmith/threads/TransposeThread.java
 * is part of the Voicesmith project
 * <http://voicesmith.jurihock.de>
 * 
 * Copyright (C) 2011-2012 Juergen Hock
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/

package de.jurihock.voicesmith.threads;

import static de.jurihock.voicesmith.dsp.Math.pow;
import static de.jurihock.voicesmith.dsp.Math.round;

import java.util.concurrent.locks.ReentrantLock;

import android.content.Context;
import de.jurihock.voicesmith.Preferences;
import de.jurihock.voicesmith.Preferences.FrameType;
import de.jurihock.voicesmith.Utils;
import de.jurihock.voicesmith.dsp.cola.ColaPostprocessor;
import de.jurihock.voicesmith.dsp.cola.ColaPreprocessor;
import de.jurihock.voicesmith.dsp.dafx.NativeResampleProcessor;
import de.jurihock.voicesmith.dsp.dafx.NativeTimescaleProcessor;
import de.jurihock.voicesmith.io.AudioDevice;

public final class TransposeThread extends AudioThread
{
	private static final ReentrantLock	lock			= new ReentrantLock(
															true);

	private int							semitones;

	private ColaPreprocessor			preprocessor	= null;
	private NativeTimescaleProcessor	timescaler		= null;
	private NativeResampleProcessor		resampler		= null;
	private ColaPostprocessor			postprocessor	= null;

	private float[]						analysisFrameBuffer;
	private float[]						synthesisFrameBuffer;

	public TransposeThread(Context context, AudioDevice input, AudioDevice output)
	{
		super(context, input, output);
		setSemitones(0);
	}

	@Override
	public void dispose()
	{
		super.dispose();
		disposeProcessors();
	}

	private void disposeProcessors()
	{
		if (preprocessor != null)
		{
			preprocessor.dispose();
			preprocessor = null;
		}

		if (timescaler != null)
		{
			timescaler.dispose();
			timescaler = null;
		}

		if (resampler != null)
		{
			resampler.dispose();
			resampler = null;
		}

		if (postprocessor != null)
		{
			postprocessor.dispose();
			postprocessor = null;
		}
	}

	@Override
	protected void doProcessing()
	{
		while (!Thread.interrupted())
		{
			lock.lock();

			try
			{
				preprocessor.processFrame(analysisFrameBuffer);
				timescaler.processFrame(analysisFrameBuffer);
				resampler.processFrame(analysisFrameBuffer,
					synthesisFrameBuffer);
				postprocessor.processFrame(synthesisFrameBuffer);
			}
			finally
			{
				lock.unlock();
			}
		}
	}

	public int getSemitones()
	{
		return semitones;
	}

	/**
	 * Allowed values: [-12..0..12]
	 * */
	public void setSemitones(int semitones)
	{
		this.semitones = semitones;
		reset(pow(2F, semitones / 12F));
	}

	/**
	 * Allowed values: [0.5..1..2]
	 * */
	private void reset(float tau)
	{
		lock.lock();

		try
		{
			Preferences preferences = new Preferences(context);

			final int frameSize = preferences.getFrameSize(FrameType.Default);
			final int hopSize = preferences.getHopSize(FrameType.Default);

			final int synthesisHopSize = hopSize;
			final int analysisHopSize = (int) round(hopSize / tau);

			final int synthesisFrameSize = (int) round(frameSize / tau);
			final int analysisFrameSize = frameSize;

			// Utils.log("Na %s Ha %s Ns %s Hs %s",
			// analysisFrameSize, analysisHopSize,
			// synthesisFrameSize, synthesisHopSize);

			disposeProcessors();

			preprocessor = new ColaPreprocessor(input,
				analysisFrameSize, analysisHopSize, true);
			timescaler = new NativeTimescaleProcessor(analysisFrameSize,
				analysisHopSize, synthesisHopSize);
			resampler = new NativeResampleProcessor(
				analysisFrameSize, synthesisFrameSize,
				analysisHopSize, true);
			postprocessor = new ColaPostprocessor(output,
				synthesisFrameSize, analysisHopSize, false);

			analysisFrameBuffer = new float[analysisFrameSize];
			synthesisFrameBuffer = new float[synthesisFrameSize];

			Utils.log("Transpose frame sizes are %s and %s.",
				analysisFrameBuffer.length, synthesisFrameBuffer.length);
		}
		finally
		{
			lock.unlock();
		}
	}
}