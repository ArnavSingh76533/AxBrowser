chrome.runtime.onInstalled.addListener(async () => {
  await chrome.storage.local.set({greeting: 'Hello from AxBrowser', installed: Date.now()});
});
chrome.contextMenus.create({id: 'hello', title: 'Hello Ax: show badge', contexts: ['page']});
chrome.contextMenus.onClicked.addListener(() => chrome.action.setBadgeText({text: 'Hi!'}));
chrome.commands.onCommand.addListener(() => chrome.action.setBadgeText({text: 'Hi!'}));
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.type !== 'hello') return;
  chrome.storage.local.get({greeting: 'Hello from AxBrowser'}).then(value => respond({greeting: value.greeting, tabId: sender.tab?.id}));
  return true;
});
