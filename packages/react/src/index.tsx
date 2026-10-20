import {useEffect, useId, useRef, useState} from 'react';
import type {FormEvent} from 'react';

export interface AgentView {
  sessionId: string;
  state:
    | 'PLANNING'
    | 'COLLECTING_FIELDS'
    | 'READY_FOR_CONFIRMATION'
    | 'EXECUTING'
    | 'DONE'
    | 'FAILED'
    | 'UNKNOWN'
    | 'CANCELLED'
    | 'EXPIRED';
  reply: string;
  errors: string[];
  confirmationId: string | null;
  expiresAt: string | null;
  operationId: string | null;
  method: string | null;
  path: string | null;
  arguments: Record<string, unknown> | null;
  httpStatus: number | null;
  result?: unknown;
  catalogVersion?: string;
}
export interface AgentChatProps {
  /** Example: /api/text-agent. Configure cross-origin CORS in the host app. */
  baseUrl?: string;
  getHeaders?: () => HeadersInit | Promise<HeadersInit>;
  title?: string;
  /** Unique per authenticated user and backend. Stores only the session ID in sessionStorage. */
  storageKey?: string;
  fieldLabels?: Record<string, string>;
  /** Remount with key={userId} when identity changes. */
  onExecuted?: (result: AgentView) => void;
}
export function AgentChat({
  baseUrl = '/api/text-agent',
  getHeaders,
  title = 'Помощник',
  storageKey,
  fieldLabels = {},
  onExecuted,
}: AgentChatProps) {
  const [open, setOpen] = useState(false);
  const [input, setInput] = useState('');
  const [view, setView] = useState<AgentView | null>(null);
  const [history, setHistory] = useState<{role: string; text: string}[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [uncertain, setUncertain] = useState(false);
  const [now, setNow] = useState(Date.now());
  const id = useId();
  const lock = useRef(false);
  const mounted = useRef(true);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const launchRef = useRef<HTMLButtonElement>(null);
  const historyRef = useRef<HTMLDivElement>(null);
  const notified = useRef<string | null>(null);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  useEffect(() => {
    if (open) inputRef.current?.focus();
  }, [open]);
  useEffect(() => {
    historyRef.current?.scrollTo?.(0, historyRef.current.scrollHeight);
  }, [history, view]);
  useEffect(() => {
    if (!open) return;
    const t = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(t);
  }, [open]);
  useEffect(() => {
    if (!storageKey) return;
    let session: string | null = null;
    try {
      session = sessionStorage.getItem(storageKey);
    } catch {
      /* Storage may be disabled. */
    }
    if (session)
      void run(() => request(`/sessions/${encodeURIComponent(session!)}`));
  }, [storageKey]);
  useEffect(() => {
    if (!open || !view || !['EXECUTING', 'PLANNING'].includes(view.state))
      return;
    const timer = setInterval(() => {
      if (!lock.current) void run(() => request(`/sessions/${view.sessionId}`));
    }, 5000);
    return () => clearInterval(timer);
  }, [open, view?.sessionId, view?.state]);
  const waiting = view?.state === 'EXECUTING' || view?.state === 'PLANNING';
  const blocked = busy || uncertain || waiting || view?.state === 'UNKNOWN';
  const expired = !!view?.expiresAt && now >= Date.parse(view.expiresAt);
  async function request(path: string, payload?: unknown): Promise<AgentView> {
    const headers = new Headers(await getHeaders?.());
    if (payload !== undefined) headers.set('Content-Type', 'application/json');
    const response = await fetch(`${baseUrl.replace(/\/$/, '')}${path}`, {
      method: payload === undefined ? 'GET' : 'POST',
      headers,
      credentials: 'same-origin',
      body: payload === undefined ? undefined : JSON.stringify(payload),
      signal: AbortSignal.timeout(60000),
    });
    if (!response.ok) {
      const body = (await response.json().catch(() => ({}))) as {
        message?: string;
      };
      throw Object.assign(
        new Error(body.message ?? `Ошибка запроса (${response.status})`),
        {status: response.status},
      );
    }
    return (await response.json()) as AgentView;
  }
  async function run(action: () => Promise<AgentView>, mayExecute = false) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError('');
    try {
      const next = await action();
      if (!mounted.current) return;
      if (storageKey) {
        try {
          sessionStorage.setItem(storageKey, next.sessionId);
        } catch {
          /* Optional persistence. */
        }
      }
      setView(next);
      setUncertain(false);
      setNow(Date.now());
      setHistory(h =>
        h.at(-1)?.text === next.reply
          ? h
          : [...h.slice(-39), {role: 'Помощник', text: next.reply}],
      );
      if (next.state === 'DONE' && next.confirmationId !== notified.current) {
        notified.current = next.confirmationId;
        try {
          onExecuted?.(next);
        } catch {
          /* Host refresh failures must not re-enable execution. */
        }
      }
    } catch (e) {
      if ((e as {status?: number})?.status === 404 && storageKey) {
        try {
          sessionStorage.removeItem(storageKey);
        } catch {
          /* Optional persistence. */
        }
        if (mounted.current) {
          setView(null);
          setUncertain(false);
        }
      }
      if (mounted.current) {
        setError(e instanceof Error ? e.message : 'Не удалось получить ответ');
        if (mayExecute) setUncertain(true);
      }
    } finally {
      lock.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  function send(e: FormEvent) {
    e.preventDefault();
    if (!input.trim() || lock.current || blocked) return;
    const message = input.trim();
    setInput('');
    setHistory(h => [...h.slice(-39), {role: 'Вы', text: message}]);
    // Hide stale confirmation immediately, including when the model request fails.
    setView(v =>
      v ? {...v, state: 'COLLECTING_FIELDS', confirmationId: null} : v,
    );
    void run(() =>
      request('/messages', {sessionId: view?.sessionId ?? null, message}),
    );
  }
  function renderValue(value: unknown): React.ReactNode {
    if (value === null) return 'Не указано';
    if (Array.isArray(value))
      return (
        <ol>
          {value.map((v, i) => (
            <li key={i}>{renderValue(v)}</li>
          ))}
        </ol>
      );
    if (typeof value === 'object')
      return (
        <dl className="ta-fields">
          {Object.entries(value as Record<string, unknown>).map(([k, v]) => (
            <div key={k}>
              <dt>{fieldLabels[k] ?? k}</dt>
              <dd>{renderValue(v)}</dd>
            </div>
          ))}
        </dl>
      );
    return typeof value === 'boolean' ? (value ? 'Да' : 'Нет') : String(value);
  }
  function close() {
    setOpen(false);
    launchRef.current?.focus();
  }
  return (
    <div className="ta-widget">
      <button
        ref={launchRef}
        className="ta-launch"
        aria-expanded={open}
        aria-controls={id}
        onClick={() => (open ? close() : setOpen(true))}
      >
        {open ? 'Закрыть помощника' : title}
      </button>
      {open && (
        <section
          id={id}
          className="ta-panel"
          aria-label={title}
          onKeyDown={e => {
            if (e.key === 'Escape') close();
          }}
        >
          <header>
            <div>
              <strong>{title}</strong>
              <small>Опишите действие. Проверьте данные. Подтвердите.</small>
            </div>
            <button aria-label="Закрыть" onClick={close}>
              ×
            </button>
          </header>
          <div
            className="ta-history"
            ref={historyRef}
            role="log"
            aria-live="polite"
            aria-relevant="additions text"
          >
            {!history.length && (
              <p className="ta-hint">
                Например: «Создай арендатора Марию Иванову, телефон
                +79001112233»
              </p>
            )}
            {history.map((m, i) => (
              <div
                className={`ta-message ${m.role === 'Вы' ? 'ta-user' : ''}`}
                key={i}
              >
                <small>{m.role}</small>
                <p>{m.text}</p>
              </div>
            ))}
            {!!view?.errors.length && (
              <ul className="ta-errors">
                {view.errors.map((e, i) => (
                  <li key={i}>{e}</li>
                ))}
              </ul>
            )}
            {view?.state === 'READY_FOR_CONFIRMATION' && (
              <div className="ta-preview">
                <strong>Проверьте данные</strong>
                {Object.entries(view.arguments ?? {}).map(([key, value]) => (
                  <div key={key}>
                    <small>
                      {key === 'body'
                        ? 'Данные'
                        : key === 'path'
                          ? 'Объект'
                          : 'Параметры'}
                    </small>
                    <div>{renderValue(value)}</div>
                  </div>
                ))}
                <p>
                  {expired
                    ? 'Подтверждение истекло. Отправьте сообщение заново.'
                    : 'Запрос будет отправлен после подтверждения.'}
                </p>
                <div className="ta-actions">
                  <button
                    disabled={busy || expired || uncertain}
                    onClick={() =>
                      void run(
                        () =>
                          request(`/sessions/${view.sessionId}/confirm`, {
                            confirmationId: view.confirmationId,
                          }),
                        true,
                      )
                    }
                  >
                    Подтвердить
                  </button>
                  <button
                    disabled={busy || uncertain}
                    onClick={() =>
                      void run(() =>
                        request(`/sessions/${view.sessionId}/cancel`, {}),
                      )
                    }
                  >
                    Отменить
                  </button>
                </div>
              </div>
            )}
            {view?.state === 'COLLECTING_FIELDS' && (
              <button
                disabled={busy || uncertain}
                onClick={() =>
                  void run(() =>
                    request(`/sessions/${view.sessionId}/cancel`, {}),
                  )
                }
              >
                Отменить действие
              </button>
            )}
            {uncertain && (
              <div role="alert">
                <p>
                  Ответ на подтверждение не получен. Сначала проверьте состояние
                  операции.
                </p>
                <button
                  disabled={busy}
                  onClick={() =>
                    void run(() => request(`/sessions/${view!.sessionId}`))
                  }
                >
                  Проверить состояние
                </button>
              </div>
            )}
            {waiting && (
              <p role="status">
                {view?.state === 'PLANNING'
                  ? 'Готовлю запрос…'
                  : 'Выполняю запрос…'}{' '}
                Состояние обновляется автоматически.
              </p>
            )}
            {view?.state === 'DONE' && view.result != null && (
              <div className="ta-preview">
                <strong>Результат</strong>
                {renderValue(view.result)}
              </div>
            )}
            {view?.state === 'UNKNOWN' && (
              <div role="alert">
                <p>
                  Не повторяйте операцию, пока не проверите результат в
                  приложении.
                </p>
                <button
                  onClick={() => {
                    setView(null);
                    setUncertain(false);
                    if (storageKey) {
                      try {
                        sessionStorage.removeItem(storageKey);
                      } catch {
                        /* Optional persistence. */
                      }
                    }
                  }}
                >
                  Я проверил результат — новый запрос
                </button>
              </div>
            )}
          </div>
          {error && (
            <p className="ta-error" role="alert">
              {error}
            </p>
          )}
          <form onSubmit={send}>
            <label htmlFor={`${id}-input`}>Ваш запрос</label>
            <textarea
              id={`${id}-input`}
              ref={inputRef}
              value={input}
              maxLength={8000}
              disabled={blocked}
              onChange={e => setInput(e.target.value)}
              placeholder="Что нужно сделать?"
              rows={2}
            />
            <button disabled={blocked || !input.trim()} type="submit">
              {busy ? 'Обрабатываю…' : 'Отправить'}
            </button>
          </form>
        </section>
      )}
    </div>
  );
}
