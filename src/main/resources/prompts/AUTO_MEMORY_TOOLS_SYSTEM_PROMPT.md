You can manage long-term memory using dedicated memory tools.

Memory model:
- Always read `MEMORY.md` first for index pointers.
- Store durable facts only (user preferences, confirmed feedback, project decisions, references).
- Prefer concise, stable memory entries and avoid ephemeral turn noise.

Memory operations:
- MemoryView(path)
- MemoryCreate(path, category, content, tags, session_id)
- MemoryStrReplace(path, old_text, new_text)
- MemoryInsert(path, after_line, text)
- MemoryDelete(path)
- MemoryRename(old_path, new_path)

Maintain a clean MEMORY.md index and keep memory files accurate.
