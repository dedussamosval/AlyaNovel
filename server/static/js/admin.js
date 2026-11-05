(() => {
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

  const toastNode = $("#toast");
  let toastTimer;

  function toast(message) {
    if (!toastNode || !message) return;
    toastNode.textContent = message;
    toastNode.classList.add("visible");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => toastNode.classList.remove("visible"), 4000);
  }

  const serverMessage = $("#server-message");
  if (serverMessage?.dataset.message) {
    toast(serverMessage.dataset.message);
    history.replaceState(null, "", location.pathname);
  }

  const form = $("#chapter-form");
  if (!form) return;

  const volumeSelect = $("#volume-select");
  const typeSelect = $("#chapter-type");
  const numberInput = $("#cnum");
  const codeNode = $("#chapter-code");
  const replaceWarning = $("#replace-warning");
  const ranobeFields = $("#ranobe-fields");
  const mangaFields = $("#manga-fields");
  const textArea = $("#chapter-text");
  const statsNode = $("#text-stats");
  const draftStatus = $("#draft-status");
  const imageInput = $("#manga-images");
  const previewNode = $("#manga-preview");
  const fileInfo = $("#manga-file-info");
  const saveButton = $("#save-chapter");
  const progressWrap = $("#upload-progress");
  const progressBar = $("#upload-progress-bar");
  const progressLabel = $("#upload-progress-label");

  let currentDraftKey = "";
  let draftTimer;
  let loadedText = textArea.value;
  let submitting = false;
  let objectUrls = [];

  function selectedVolume() {
    return volumeSelect.selectedOptions[0] || null;
  }

  function codeFor(kind, volume, type, number) {
    const prefix = `${kind === "ranobe" ? "R" : "M"}${String(volume).padStart(2, "0")}`;
    if (type === "prologue") return `${prefix}-P`;
    if (type === "epilogue") return `${prefix}-E`;

    const n = Number.parseInt(number, 10);
    if (!Number.isInteger(n) || n < 1) return "—";

    if (type === "extra") return `${prefix}-A${String(n).padStart(2, "0")}`;
    return `${prefix}-${String(n).padStart(2, "0")}`;
  }

  function updateDraftKey(loadDraft = false) {
    const previousKey = currentDraftKey;
    const option = selectedVolume();
    if (!option) {
      currentDraftKey = "";
      return;
    }

    const needsNumber = !["prologue", "epilogue"].includes(typeSelect.value);
    const number = needsNumber ? numberInput.value : "0";
    currentDraftKey =
      `draft:${option.value}:${typeSelect.value}:${number || "new"}`;

    if (loadDraft && currentDraftKey !== previousKey) {
      try {
        const saved = localStorage.getItem(currentDraftKey);
        textArea.value = saved ?? "";
        loadedText = textArea.value;
        updateTextStats();
        draftStatus.textContent = saved ? "Черновик восстановлен" : "";
      } catch {
        draftStatus.textContent = "Локальное хранилище недоступно";
      }
    }
  }

  function updateTextStats() {
    const text = textArea.value;
    const words = text.trim() ? text.trim().split(/\s+/u).length : 0;
    statsNode.textContent = `${text.length} символов · ${words} слов`;
  }

  function saveDraftSoon() {
    clearTimeout(draftTimer);
    draftStatus.textContent = "Черновик сохраняется…";
    draftTimer = setTimeout(() => {
      if (!currentDraftKey) return;
      try {
        localStorage.setItem(currentDraftKey, textArea.value);
        draftStatus.textContent = "Черновик сохранён";
      } catch {
        draftStatus.textContent = "Не удалось сохранить черновик";
      }
    }, 400);
  }

  function updateFormMode() {
    const option = selectedVolume();
    const isRanobe = option?.dataset.kind === "ranobe";
    ranobeFields.hidden = !isRanobe;
    mangaFields.hidden = isRanobe;

    const needsNumber = !["prologue", "epilogue"].includes(typeSelect.value);
    numberInput.disabled = !needsNumber;
    numberInput.required = needsNumber;
    if (!needsNumber) numberInput.value = "";

    updateDraftKey(true);
    updateChapterCode();
  }

  function updateChapterCode() {
    const option = selectedVolume();
    if (!option) {
      codeNode.textContent = "—";
      replaceWarning.hidden = true;
      return;
    }

    const code = codeFor(
      option.dataset.kind,
      option.dataset.volume,
      typeSelect.value,
      numberInput.value
    );
    codeNode.textContent = code;

    let existing = [];
    try {
      existing = JSON.parse(option.dataset.existing || "[]");
    } catch {
      existing = [];
    }

    const number = ["prologue", "epilogue"].includes(typeSelect.value)
      ? 0
      : Number.parseInt(numberInput.value, 10);

    const willReplace = existing.some(
      (chapter) =>
        chapter.type === typeSelect.value &&
        Number(chapter.number) === number
    );
    replaceWarning.hidden = code === "—" || !willReplace;
  }

  volumeSelect.addEventListener("change", updateFormMode);
  typeSelect.addEventListener("change", updateFormMode);
  numberInput.addEventListener("input", () => {
    updateDraftKey(true);
    updateChapterCode();
  });

  textArea.addEventListener("input", () => {
    updateTextStats();
    saveDraftSoon();
  });

  function fileNameCompare(a, b) {
    return a.name.localeCompare(b.name, undefined, {
      numeric: true,
      sensitivity: "base",
    });
  }

let selectedImages = [];
let draggedIndex = null;

function clearPreviews() {
  objectUrls.forEach((url) => URL.revokeObjectURL(url));
  objectUrls = [];
  previewNode.replaceChildren();
}

function syncInputFiles() {
  // Перекладываем выбранные файлы в том же порядке, что и миниатюры.
  const transfer = new DataTransfer();
  selectedImages.forEach((file) => transfer.items.add(file));
  imageInput.files = transfer.files;
}

function renderImagePreviews() {
  clearPreviews();

  for (const [index, file] of selectedImages.entries()) {
    const url = URL.createObjectURL(file);
    objectUrls.push(url);

    const figure = document.createElement("figure");
    figure.className = "manga-page";
    figure.draggable = true;
    figure.dataset.index = String(index);

    const image = document.createElement("img");
    image.src = url;
    image.alt = `Страница ${index + 1}`;
    image.loading = "lazy";

    const caption = document.createElement("figcaption");
    caption.textContent = `${index + 1}. ${file.name}`;

    const controls = document.createElement("div");
    controls.className = "page-controls";

    const up = document.createElement("button");
    up.type = "button";
    up.classList.add("arrow-btn", "arrow-up");
    // up.textContent = "↑";
    up.setAttribute("aria-label", `Переместить ${file.name} выше`);
    up.disabled = index === 0;
    up.addEventListener("click", () => moveImage(index, index - 1));

    const down = document.createElement("button");
    down.type = "button";
    down.classList.add("arrow-btn", "arrow-down");
    down.setAttribute("aria-label", `Переместить ${file.name} ниже`);
    down.disabled = index === selectedImages.length - 1;
    down.addEventListener("click", () => moveImage(index, index + 1));

    controls.append(up, down);
    figure.append(image, caption, controls);

    figure.addEventListener("dragstart", (event) => {
      draggedIndex = index;
      event.dataTransfer.effectAllowed = "move";
      event.dataTransfer.setData("text/plain", String(index));
    });

    figure.addEventListener("dragover", (event) => {
      event.preventDefault();
      event.dataTransfer.dropEffect = "move";
    });

    figure.addEventListener("drop", (event) => {
      event.preventDefault();

      const from = draggedIndex;
      const to = Number(figure.dataset.index);
      if (from === null || from === to) return;

      moveImage(from, to);
    });

    previewNode.append(figure);
  }
}

function moveImage(from, to) {
  if (
    from < 0 ||
    to < 0 ||
    from >= selectedImages.length ||
    to >= selectedImages.length
  ) {
    return;
  }

  const [file] = selectedImages.splice(from, 1);
  selectedImages.splice(to, 0, file);

  syncInputFiles();
  renderImagePreviews();
}

imageInput.addEventListener("change", () => {
  // Начальная сортировка остаётся такой же, как была на сервере.
  selectedImages = [...imageInput.files].sort((a, b) =>
    a.name.localeCompare(b.name, undefined, {
      numeric: true,
      sensitivity: "base",
    })
  );

  const totalBytes = selectedImages.reduce(
    (total, file) => total + file.size,
    0
  );
  const totalMb = totalBytes / (1024 * 1024);
  const limitBytes = 300 * 1024 * 1024;

  fileInfo.textContent =
    `${selectedImages.length} файлов · ${totalMb.toFixed(1)} МБ · ` +
    "перетащите миниатюры или используйте стрелки";

  if (totalBytes > limitBytes) {
    fileInfo.textContent += " — превышен лимит загрузки 300 МБ";
    fileInfo.classList.add("file-error");
    saveButton.disabled = true;
  } else {
    fileInfo.classList.remove("file-error");
    saveButton.disabled = false;
  }

  // Записываем в input начальный отсортированный порядок.
  syncInputFiles();
  renderImagePreviews();
});

  const searchInput = $("#chapter-search");
  searchInput?.addEventListener("input", () => {
    const query = searchInput.value.trim().toLocaleLowerCase();

    $$(".volume-card").forEach((card) => {
      const rows = $$(".chapter-row", card);
      let matchingRows = 0;

      rows.forEach((row) => {
        const matches = row.dataset.chapterSearch
          .toLocaleLowerCase()
          .includes(query);
        row.hidden = !matches;
        if (matches) matchingRows += 1;
      });

      const volumeMatches = card.dataset.volumeSearch
        .toLocaleLowerCase()
        .includes(query);

      card.hidden = Boolean(query) && !volumeMatches && matchingRows === 0;
      if (query && matchingRows > 0) card.open = true;
    });
  });

  const dialog = $("#confirm-dialog");
  let pendingDeleteForm = null;

  $$(".delete-form").forEach((deleteForm) => {
    deleteForm.addEventListener("submit", (event) => {
      if (deleteForm.dataset.confirmed === "true") {
        deleteForm.dataset.confirmed = "false";
        return;
      }

      event.preventDefault();
      pendingDeleteForm = deleteForm;
      $("#confirm-title", dialog).textContent =
        deleteForm.dataset.confirmTitle || "Подтвердите удаление";
      $("#confirm-detail", dialog).textContent =
        deleteForm.dataset.confirmDetail || "";

      if (typeof dialog.showModal === "function") {
        dialog.showModal();
      } else if (window.confirm(`${$("#confirm-title", dialog).textContent}\n${$("#confirm-detail", dialog).textContent}`)) {
        deleteForm.dataset.confirmed = "true";
        deleteForm.requestSubmit();
      }
    });
  });

  dialog.addEventListener("close", () => {
    if (dialog.returnValue === "confirm" && pendingDeleteForm) {
      pendingDeleteForm.dataset.confirmed = "true";
      pendingDeleteForm.requestSubmit();
    }
    pendingDeleteForm = null;
  });

  function restoreSaveButton() {
    submitting = false;
    saveButton.disabled = false;
    saveButton.textContent = "Сохранить главу";
    progressWrap.hidden = true;
    progressBar.value = 0;
  }

  form.addEventListener("submit", (event) => {
    event.preventDefault();
    if (submitting) return;

    submitting = true;
    clearTimeout(draftTimer);
    if (currentDraftKey) {
      try {
        localStorage.setItem(currentDraftKey, textArea.value);
      } catch {
        // Загрузка должна работать и при отключённом localStorage.
      }
    }

    saveButton.disabled = true;
    saveButton.textContent = "Сохраняю…";
    progressWrap.hidden = false;
    progressLabel.textContent = "Подготовка загрузки…";

    const xhr = new XMLHttpRequest();
    xhr.open("POST", form.action);
    xhr.responseType = "document";

    xhr.upload.addEventListener("progress", (progressEvent) => {
      if (!progressEvent.lengthComputable) {
        progressLabel.textContent = "Загрузка данных…";
        return;
      }

      const percent = Math.round(
        (progressEvent.loaded / progressEvent.total) * 100
      );
      progressBar.value = percent;
      progressLabel.textContent = `Загружено ${percent}%`;
    });

    xhr.addEventListener("load", () => {
      const responseUrl = new URL(xhr.responseURL || location.href);
      const message = responseUrl.searchParams.get("msg") || "";

      if (xhr.status >= 200 && xhr.status < 400) {
        if (message.includes("Глава сохранена")) {
          if (currentDraftKey) {
            try {
              localStorage.removeItem(currentDraftKey);
            } catch {
              // Черновик не должен мешать переходу после успешной загрузки.
            }
          }
        }

        location.assign(responseUrl.href);
        return;
      }

      restoreSaveButton();
      toast("Не удалось сохранить главу. Проверьте данные и попробуйте ещё раз.");
    });

    xhr.addEventListener("error", () => {
      restoreSaveButton();
      toast("Ошибка сети при сохранении главы. Черновик сохранён.");
    });

    xhr.addEventListener("abort", () => {
      restoreSaveButton();
      toast("Загрузка отменена.");
    });

    xhr.send(new FormData(form));
  });

  document.addEventListener("keydown", (event) => {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === "s") {
      event.preventDefault();
      form.requestSubmit();
    }
  });

  window.addEventListener("beforeunload", (event) => {
    if (!submitting && textArea.value !== loadedText) {
      event.preventDefault();
      event.returnValue = "";
    }
  });

  window.addEventListener("pageshow", () => {
    restoreSaveButton();
  });

  updateFormMode();
  updateTextStats();
})();