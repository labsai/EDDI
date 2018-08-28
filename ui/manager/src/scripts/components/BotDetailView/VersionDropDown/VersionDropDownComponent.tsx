import * as React from 'react';
import * as Radium from 'radium';
import * as renderIf from 'render-if';
import styles from './VersionDropDown.styles';
import { Component, compose, pure, setDisplayName } from 'recompose';

interface IState {}
interface IProps {
  version: string;
}

class VersionDropDownComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {};
  }
  render() {
    const dropDownVisibility = Radium.getState(
      this.state,
      'dropDown',
      ':hover',
    );
    const dropDownArrow = dropDownVisibility
      ? styles.dropDownArrowUp
      : styles.dropDownArrowDown;

    return (
      <div>
        <div key={'dropDown'} style={styles.dropDown}>
          <div style={styles.dropDownSelected}>
            <div style={styles.dropDownSelectedVersion}>{`V${
              this.props.version
            }`}</div>
            <div style={dropDownArrow} />
          </div>
          {renderIf(dropDownVisibility)(() => (
            <div style={styles.dropDownContent}>
              <button key={'4'} style={styles.button}>
                {`V${this.props.version}`}
              </button>
            </div>
          ))}
        </div>
      </div>
    );
  }
}

const ComposedVersionDropDownComponent: Component<IProps> = compose<
  IProps,
  IProps
>(pure, Radium, setDisplayName('VersionDropDownComponent'))(
  VersionDropDownComponent,
);

export default ComposedVersionDropDownComponent;
