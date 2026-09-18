#ifndef STEREO_FRAMES_H
#define STEREO_FRAMES_H

#include <map>
#include <memory>
#include <set>
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

	/** The kept copy belonging to this N64 frame buffer, or null if it was not captured. */
	FrameBuffer * leftEye(const FrameBuffer * _pBuffer) const;

	/** Forgets the copy, e.g. when stereo is switched off. */
	void reset();

	void destroy();

private:
	StereoFrames() = default;
	StereoFrames(const StereoFrames &) = delete;

	std::unique_ptr<FrameBuffer> _createBuffer(const FrameBuffer * _pMainBuffer);

	// A frame may touch several color images (for example a scene and an overlay). Keeping one
	// global copy made the last image captured for the left eye get paired with an unrelated main
	// image at VI time.
	std::map<u32, std::unique_ptr<FrameBuffer>> m_leftEyes;
	std::set<u32> m_capturedThisFrame;
};

#endif // STEREO_FRAMES_H
