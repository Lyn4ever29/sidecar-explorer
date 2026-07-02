# Implementation Notes

## Project Model Safety

`文件` never calls IntelliJ APIs that mutate the project model, such as adding content roots, modules, libraries, Maven projects, or Gradle linked projects.

External paths are stored only in `SidecarProjectService` through `PersistentStateComponent`, using `sidecarExplorer.xml` as project-level plugin state.

## File Browsing

- Roots are resolved with `LocalFileSystem.refreshAndFindFileByPath`.
- Missing roots remain in saved state and are rendered with a missing label.
- Children load lazily when a directory node expands.
- The MVP filters `.git`, `.idea`, `build`, `node_modules`, and `target`.
- Sorting is directory-first, then name ascending.

## Opening and Reveal

- Files open through `FileEditorManager.openFile`.
- Directories expand/collapse instead of being opened as projects.
- Reveal uses the host desktop file manager and opens the selected directory or a file's parent directory.

## Persistence

Saved entry fields:

- `path`
- `displayName`
- `addedAt`

Duplicate roots are ignored after normalized path comparison.
