chrome.runtime.sendMessage({type: 'hello'}).then(reply => {
  const badge = document.createElement('div');
  badge.id = 'hello-ax-badge'; badge.textContent = reply.greeting;
  document.body.appendChild(badge);
}).catch(console.error);
chrome.runtime.onMessage.addListener((message, _sender, respond) => {
  if (message.type === 'page-title') respond({title: document.title});
});
