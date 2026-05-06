import { Routes } from '@angular/router';

export const routes: Routes = [
  {
    path: '',
    redirectTo: 'upload',
    pathMatch: 'full',
  },
  {
    path: 'upload',
    loadComponent: () =>
      import('./features/upload/upload-page/upload-page')
        .then((m) => m.UploadPage),
  },
  {
    path: 'analysis/:sessionId',
    loadComponent: () =>
      import('./features/analysis/analysis-page/analysis-page')
        .then((m) => m.AnalysisPage),
  },
  {
    path: 'conversation/:sessionId',
    loadComponent: () =>
      import('./features/conversation/conversation-page/conversation-page')
        .then((m) => m.ConversationPage),
  },
  {
    path: 'result/:sessionId',
    loadComponent: () =>
      import('./features/result/result-page/result-page')
        .then((m) => m.ResultPage),
  },
  {
    path: '**',
    redirectTo: 'upload',
  },
];
