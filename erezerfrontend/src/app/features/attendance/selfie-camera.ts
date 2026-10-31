import { afterNextRender, ChangeDetectionStrategy, Component, ElementRef, inject, DestroyRef, output, signal, viewChild } from '@angular/core';

const MAX_SIDE = 720;
const JPEG_QUALITY = 0.8;

/** A live photo from the front camera. There is no gallery option, so an old picture can't be used. */
@Component({
  selector: 'app-selfie-camera',
  template: `
    <div class="fixed inset-0 z-[85] flex items-center justify-center bg-black/80 p-4">
      <div class="card w-full max-w-sm p-4">
        <h2 class="text-lg font-semibold text-white">Take your photo</h2>
        @if (problem()) {
          <p class="mt-3 text-sm text-rose-300">{{ problem() }}</p>
        } @else {
          <video #video autoplay playsinline muted class="mt-3 aspect-square w-full rounded-lg bg-black object-cover" style="transform: scaleX(-1)"></video>
        }
        <div class="mt-4 flex justify-end gap-2">
          <button type="button" class="btn-secondary" (click)="cancelled.emit()">Cancel</button>
          @if (!problem()) {
            <button type="button" class="btn-primary" [disabled]="!ready()" (click)="capture()"><i class="fas fa-camera"></i> Take photo</button>
          }
        </div>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SelfieCamera {
  readonly captured = output<Blob>();
  readonly cancelled = output<void>();

  private readonly video = viewChild<ElementRef<HTMLVideoElement>>('video');
  protected readonly ready = signal(false);
  protected readonly problem = signal('');
  private stream: MediaStream | null = null;
  private destroyed = false;

  constructor() {
    afterNextRender(() => void this.start());
    inject(DestroyRef).onDestroy(() => {
      this.destroyed = true;
      this.stop();
    });
  }

  private async start(): Promise<void> {
    if (!navigator.mediaDevices?.getUserMedia) {
      this.problem.set('This browser cannot open the camera. Open the panel in Chrome or Safari.');
      return;
    }
    try {
      this.stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'user', width: { ideal: MAX_SIDE } },
        audio: false,
      });
      if (this.destroyed) {
        this.stop();
        return;
      }
      const video = this.video()!.nativeElement;
      video.srcObject = this.stream;
      video.onloadedmetadata = () => this.ready.set(true);
    } catch {
      this.problem.set('The camera could not be opened. Allow camera access for this site in your browser settings, then try again.');
    }
  }

  protected capture(): void {
    const video = this.video()!.nativeElement;
    const scale = Math.min(1, MAX_SIDE / Math.max(video.videoWidth, video.videoHeight));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(video.videoWidth * scale);
    canvas.height = Math.round(video.videoHeight * scale);
    canvas.getContext('2d')!.drawImage(video, 0, 0, canvas.width, canvas.height);
    canvas.toBlob(
      (blob) => {
        if (blob) {
          this.stop();
          this.captured.emit(blob);
        } else {
          this.problem.set('The photo could not be taken. Please try again.');
        }
      },
      'image/jpeg',
      JPEG_QUALITY,
    );
  }

  private stop(): void {
    this.stream?.getTracks().forEach((track) => track.stop());
    this.stream = null;
  }
}
