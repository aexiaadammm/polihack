import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { AnalysisService } from '../../../core/services/analysis.service';

@Component({
  selector: 'app-upload-page',
  imports: [FormsModule],
  templateUrl: './upload-page.html',
  styleUrl: './upload-page.scss',
})
export class UploadPage {
  repoUrl = '';
  accessToken = '';
  branch = 'main';

  isLoading = false;
  errorMessage = '';

  constructor(
    private readonly router: Router,
    private readonly analysisService: AnalysisService,
  ) {}

  analyzeRepository() {
    this.errorMessage = '';

    if (!this.repoUrl.trim()) {
      this.errorMessage = 'Repository URL is required.';
      return;
    }

    this.isLoading = true;

    this.analysisService
      .analyzeRepository({
        repoUrl: this.repoUrl,
        branch: this.branch,
        token: this.accessToken || undefined,
      })
      .subscribe({
        next: (summary) => {
          this.isLoading = false;
          this.router.navigate(['/analysis', summary.sessionId]);
        },
        error: () => {
          this.isLoading = false;
          this.errorMessage = 'Backend analysis failed. Check that http://localhost:8080 is running and /analyze-repo matches the contract.';
        },
      });
  }
}
