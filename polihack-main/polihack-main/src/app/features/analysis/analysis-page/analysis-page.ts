import { Component, OnInit, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { AnalysisService, AnalysisSummary } from '../../../core/services/analysis.service';

@Component({
  selector: 'app-analysis-page',
  imports: [],
  templateUrl: './analysis-page.html',
  styleUrl: './analysis-page.scss',
})
export class AnalysisPage implements OnInit {
  readonly analysis = signal<AnalysisSummary | null>(null);
  readonly loading = signal(true);
  readonly errorMessage = signal('');
  sessionId = '';

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly analysisService: AnalysisService,
  ) {}

  ngOnInit(): void {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId') ?? '';
    this.analysisService.getAnalysis(this.sessionId).subscribe({
      next: (analysis) => {
        this.analysis.set(analysis);
        this.loading.set(false);
      },
      error: () => {
        this.errorMessage.set('Could not load this analysis from the backend. Check /analysis/{sessionId}.');
        this.loading.set(false);
      },
    });
  }

  continue(): void {
    this.router.navigate(['/conversation', this.sessionId]);
  }

  restart(): void {
    this.router.navigate(['/upload']);
  }
}