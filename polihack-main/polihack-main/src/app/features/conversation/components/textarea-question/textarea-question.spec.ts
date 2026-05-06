import { ComponentFixture, TestBed } from '@angular/core/testing';

import { TextareaQuestion } from './textarea-question';

describe('TextareaQuestion', () => {
  let component: TextareaQuestion;
  let fixture: ComponentFixture<TextareaQuestion>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [TextareaQuestion],
    }).compileComponents();

    fixture = TestBed.createComponent(TextareaQuestion);
    component = fixture.componentInstance;
    await fixture.whenStable();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
