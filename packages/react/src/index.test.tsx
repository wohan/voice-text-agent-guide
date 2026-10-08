// @vitest-environment jsdom
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { AgentChat } from './index';
const ready = { sessionId:'s1', state:'READY_FOR_CONFIRMATION', reply:'Подтвердите: создать арендатора', errors:[], confirmationId:'c1', expiresAt:'2099-01-01T00:00:00Z', arguments:{body:{fullName:'Мария',phone:'+79001112233'}} };
const mockFetch = vi.fn();
beforeEach(() => { mockFetch.mockReset(); vi.stubGlobal('fetch', mockFetch); });
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
function reply(value: unknown) { return {ok:true,json:async()=>value}; }
async function send() {
  fireEvent.click(screen.getByText('Помощник'));
  fireEvent.change(screen.getByLabelText('Ваш запрос'), {target:{value:'Создай арендатора'}});
  fireEvent.click(screen.getByText('Отправить'));
  await screen.findByText('Подтвердить');
}
test('sends only a proposal until explicit confirmation and uses current headers', async () => {
  const executed = vi.fn();
  mockFetch.mockResolvedValueOnce(reply(ready)).mockResolvedValueOnce(reply({...ready,state:'DONE',reply:'Готово'}));
  render(<AgentChat getHeaders={()=>({Authorization:'Bearer alice'})} onExecuted={executed}/>);
  await send(); expect(mockFetch).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByText('Подтвердить'));
  await screen.findByText('Готово');
  expect(mockFetch.mock.calls[1][0]).toBe('/api/text-agent/sessions/s1/confirm');
  expect(JSON.parse(mockFetch.mock.calls[1][1].body)).toEqual({confirmationId:'c1'});
  expect(mockFetch.mock.calls[1][1].headers.get('Authorization')).toBe('Bearer alice');
  expect(executed).toHaveBeenCalledTimes(1);
});
test('cancellation never calls confirmation endpoint', async () => {
  mockFetch.mockResolvedValueOnce(reply(ready)).mockResolvedValueOnce(reply({...ready,state:'CANCELLED',reply:'Отменено'}));
  render(<AgentChat/>); await send(); fireEvent.click(screen.getByText('Отменить')); await screen.findByText('Отменено');
  expect(mockFetch.mock.calls[1][0]).toContain('/cancel');
});
test('lost confirmation response requires a status check', async () => {
  mockFetch.mockResolvedValueOnce(reply(ready)).mockRejectedValueOnce(new Error('connection lost')).mockResolvedValueOnce(reply({...ready,state:'DONE',reply:'Готово'}));
  render(<AgentChat/>); await send(); fireEvent.click(screen.getByText('Подтвердить'));
  await screen.findByText('Проверить состояние');
  expect((screen.getByText('Подтвердить') as HTMLButtonElement).disabled).toBe(true);
  fireEvent.click(screen.getByText('Проверить состояние')); await screen.findByText('Готово');
  expect(mockFetch.mock.calls[2][1].method).toBe('GET');
});
test('edits hide stale confirmation even if model request fails', async () => {
  mockFetch.mockResolvedValueOnce(reply(ready)).mockRejectedValueOnce(new Error('model offline'));
  render(<AgentChat/>); await send();
  fireEvent.change(screen.getByLabelText('Ваш запрос'), {target:{value:'Другое имя'}}); fireEvent.click(screen.getByText('Отправить'));
  await screen.findByText('model offline'); expect(screen.queryByText('Подтвердить')).toBeNull();
});
test('expired preview cannot execute', async () => {
  mockFetch.mockResolvedValueOnce(reply({...ready,expiresAt:'2000-01-01T00:00:00Z'}));
  render(<AgentChat/>); await send();
  await waitFor(()=>expect((screen.getByText('Подтвердить') as HTMLButtonElement).disabled).toBe(true));
});
test('restores only session ID and displays backend result without another execution', async () => {
  sessionStorage.setItem('agent:alice','s1');
  mockFetch.mockResolvedValueOnce(reply({...ready,state:'DONE',reply:'Готово',result:{id:'new-tenant',fullName:'Мария'}}));
  render(<AgentChat storageKey="agent:alice"/>);
  await waitFor(()=>expect(mockFetch).toHaveBeenCalledTimes(1));
  fireEvent.click(screen.getByText('Помощник'));
  await screen.findByText('new-tenant');
  expect(mockFetch.mock.calls[0][0]).toBe('/api/text-agent/sessions/s1');
  expect(mockFetch.mock.calls[0][1].method).toBe('GET');
  expect(sessionStorage.getItem('agent:alice')).toBe('s1');
  sessionStorage.clear();
});
test('uncertain execution blocks further commands until user checks outcome', async () => {
  mockFetch.mockResolvedValueOnce(reply(ready)).mockResolvedValueOnce(reply({...ready,state:'UNKNOWN',reply:'Ответ потерян'}));
  render(<AgentChat/>);await send();fireEvent.click(screen.getByText('Подтвердить'));
  await screen.findByText('Ответ потерян');
  expect((screen.getByLabelText('Ваш запрос') as HTMLTextAreaElement).disabled).toBe(true);
  fireEvent.click(screen.getByText('Я проверил результат — новый запрос'));
  expect((screen.getByLabelText('Ваш запрос') as HTMLTextAreaElement).disabled).toBe(false);
});
