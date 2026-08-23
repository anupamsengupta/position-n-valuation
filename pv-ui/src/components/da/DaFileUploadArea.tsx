import { useCallback, useRef, useState } from 'react';
import { cn } from '@/lib/cn';
import { useDaFileUpload } from '@/hooks/useDaMutations';

const MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

export interface DaFileUploadAreaProps {
  /** Called when upload succeeds, with the returned sessionId. */
  onUploadSuccess?: (sessionId: string) => void;
}

/**
 * Drag-and-drop file upload zone for CSV auction result files.
 * Shows upload progress, success, and error inline.
 */
export function DaFileUploadArea({ onUploadSuccess }: DaFileUploadAreaProps) {
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragOver, setIsDragOver] = useState(false);
  const [clientError, setClientError] = useState<string | null>(null);

  const uploadMutation = useDaFileUpload();

  const handleFile = useCallback(
    (file: File) => {
      setClientError(null);

      // Validate file type
      if (!file.name.endsWith('.csv')) {
        setClientError('Only .csv files are accepted.');
        return;
      }

      // Validate file size
      if (file.size > MAX_FILE_SIZE_BYTES) {
        setClientError(
          `File size (${(file.size / (1024 * 1024)).toFixed(1)} MB) exceeds the 10 MB limit.`,
        );
        return;
      }

      const formData = new FormData();
      formData.append('file', file);

      uploadMutation.mutate(formData, {
        onSuccess: (data) => {
          onUploadSuccess?.(data.sessionId);
        },
      });
    },
    [uploadMutation, onUploadSuccess],
  );

  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  }, []);

  const handleDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      e.stopPropagation();
      setIsDragOver(false);

      const file = e.dataTransfer.files[0];
      if (file) {
        handleFile(file);
      }
    },
    [handleFile],
  );

  const handleInputChange = useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (file) {
        handleFile(file);
      }
      // Reset file input so re-selecting the same file triggers onChange
      if (fileInputRef.current) {
        fileInputRef.current.value = '';
      }
    },
    [handleFile],
  );

  const handleBrowseClick = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === 'Enter' || e.key === ' ') {
        e.preventDefault();
        handleBrowseClick();
      }
    },
    [handleBrowseClick],
  );

  const errorMessage = clientError ?? (uploadMutation.isError ? uploadMutation.error.message : null);

  return (
    <div
      role="region"
      aria-label="File upload"
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
      onKeyDown={handleKeyDown}
      tabIndex={0}
      className={cn(
        'relative flex flex-col items-center justify-center gap-2 p-6',
        'rounded-lg border-2 border-dashed transition-colors cursor-pointer',
        isDragOver
          ? 'border-interactive-focus bg-interactive-focus/10'
          : 'border-border-default bg-bg-secondary hover:border-text-muted',
        'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
      )}
      aria-describedby={errorMessage ? 'upload-error' : undefined}
    >
      {uploadMutation.isPending ? (
        <div className="flex items-center gap-2" role="status" aria-live="polite">
          <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-interactive-focus border-t-transparent" />
          <span className="text-sm text-text-secondary">Uploading...</span>
        </div>
      ) : (
        <>
          <span className="text-2xl text-text-muted" aria-hidden="true">
            {'\u21E7'}
          </span>
          <p className="text-sm text-text-secondary">
            Drag and drop a <span className="font-medium">.csv</span> file here
          </p>
          <p className="text-xs text-text-muted">or</p>
          <button
            type="button"
            onClick={handleBrowseClick}
            className={cn(
              'px-3 py-1.5 text-xs font-medium rounded',
              'border border-border-default bg-bg-primary text-text-primary',
              'hover:bg-bg-secondary transition-colors',
              'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-interactive-focus',
            )}
          >
            Browse
          </button>
          <p className="text-xs text-text-muted">Max file size: 10 MB</p>
        </>
      )}

      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".csv"
        onChange={handleInputChange}
        className="sr-only"
        aria-label="Select CSV file to upload"
        tabIndex={-1}
      />

      {/* Error message */}
      {errorMessage && (
        <p
          id="upload-error"
          role="alert"
          className="text-xs text-numeric-negative font-medium mt-1"
        >
          {errorMessage}
        </p>
      )}

      {/* Success indicator (brief) */}
      {uploadMutation.isSuccess && (
        <p className="text-xs text-status-settled font-medium mt-1" role="status">
          Upload successful. Session created.
        </p>
      )}
    </div>
  );
}
