import { Component, OnInit, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AnalysisService, BackendQuestion, WorkflowAnswer } from '../../../core/services/analysis.service';

@Component({
  selector: 'app-conversation-page',
  imports: [FormsModule],
  templateUrl: './conversation-page.html',
  styleUrl: './conversation-page.scss',
})
export class ConversationPage implements OnInit {
  readonly questions = signal<BackendQuestion[]>([]);
  readonly currentIndex = signal(0);
  readonly answers = signal<WorkflowAnswer[]>([]);
  readonly selectedAnswer = signal('');
  readonly freeTextAnswer = signal('');
  readonly loading = signal(true);
  readonly saving = signal(false);
  readonly errorMessage = signal('');

  readonly currentQuestion = computed(() => this.questions()[this.currentIndex()]);
  readonly generatedLines = computed(() =>
    this.answers().map((answer) => ({
      code: this.previewLine(answer),
      reason: this.previewReason(answer),
    })),
  );

  readonly selectedValue = computed(() => {
    const question = this.currentQuestion();
    return question?.options?.length ? this.selectedAnswer() : this.freeTextAnswer();
  });

  private readonly sessionId: string;

  constructor(
    private readonly route: ActivatedRoute,
    private readonly router: Router,
    private readonly analysisService: AnalysisService,
  ) {
    this.sessionId = this.route.snapshot.paramMap.get('sessionId') ?? '';
  }

  ngOnInit(): void {
    const activeQuestions = this.analysisService.activeAnalysis()?.questions ?? [];

    if (activeQuestions.length) {
      this.questions.set(activeQuestions);
      this.loading.set(false);
      return;
    }

    this.analysisService.getAnalysis(this.sessionId).subscribe({
      next: (analysis) => {
        this.questions.set(analysis.questions ?? []);
        this.loading.set(false);

        if (!analysis.questions?.length) {
          this.errorMessage.set('Backend returned no questions. Check the questions array from /analyze-repo or /analysis/{sessionId}.');
        }
      },
      error: () => {
        this.errorMessage.set('Could not load questions from the backend. Check /analysis/{sessionId}.');
        this.loading.set(false);
      },
    });
  }

  choose(option: string): void {
    this.selectedAnswer.set(option);
  }

  updateFreeText(event: Event): void {
    const element = event.target as HTMLTextAreaElement;
    this.freeTextAnswer.set(element.value);
  }

  next(): void {
    const question = this.currentQuestion();
    const answer = this.selectedValue().trim();

    if (!question || !answer) {
      return;
    }

    this.answers.update((items) => [
      ...items,
      {
        questionId: question.id,
        question: question.text,
        answer,
      },
    ]);
    this.selectedAnswer.set('');
    this.freeTextAnswer.set('');

    if (this.currentIndex() < this.questions().length - 1) {
      this.currentIndex.update((value) => value + 1);
      return;
    }

    this.saving.set(true);
    this.analysisService.submitAnswers(this.sessionId, this.answers()).subscribe({
      next: () => {
        this.saving.set(false);
        this.router.navigate(['/result', this.sessionId]);
      },
      error: () => {
        this.saving.set(false);
        this.errorMessage.set('Could not submit answers. Check POST /submit-answers.');
      },
    });
  }

  back(): void {
    if (this.currentIndex() === 0) {
      return;
    }

    this.currentIndex.update((value) => value - 1);
    this.answers.update((items) => items.slice(0, -1));
    this.selectedAnswer.set('');
    this.freeTextAnswer.set('');
  }

  private previewLine(answer: WorkflowAnswer): string {
    const stepName = answer.questionId.replace(/\W+/g, '_');
    return `workflow.configure("${stepName}", rule="${answer.answer.toLowerCase()}")`;
  }

  private previewReason(answer: WorkflowAnswer): string {
    const question = this.questions().find((item) => item.id === answer.questionId);
    return question?.whyAsked ?? 'This answer turns implicit team knowledge into a workflow rule.';
  }
}
