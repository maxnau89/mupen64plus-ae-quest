#ifndef STEREO_FRAMES_H
#define STEREO_FRAMES_H

#include <memory>
#include "Types.h"

struct FrameBuffer;

/**
 * Experimental stereoscopic 3D. The display list is walked once per eye, both walks drawing into the
 * same N64 frame buffer, so the left eye has to be put aside before the right one overwrites it.
 * What renderBuffer() then shows is the pair side by side.
 */
class StereoFrames
{
public:
	static StereoFrames & get();

	/** Keeps a copy of the buffer the first walk drew. */
	void captureLeftEye(FrameBuffer * _pBuffer);

	/** The kept copy, or null while there is none for this frame. */
	FrameBuffer * leftEye() const;

	/** Forgets the copy, e.g. when stereo is switched off. */
	void reset();

	void destroy();

private:
	StereoFrames() = default;
	StereoFrames(const StereoFrames &) = delete;

	void _createBuffer(const FrameBuffer * _pMainBuffer);

	std::unique_ptr<FrameBuffer> m_pLeftEye;
	bool m_hasLeftEye = false;
};

#endif // STEREO_FRAMES_H
