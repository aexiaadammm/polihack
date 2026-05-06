import { ComponentFixture, TestBed } from '@angular/core/testing';

import { CodePreview } from './code-preview';

describe('CodePreview', () => {
  let component: CodePreview;
  let fixture: ComponentFixture<CodePreview>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [CodePreview],
    }).compileComponents();

    fixture = TestBed.createComponent(CodePreview);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
