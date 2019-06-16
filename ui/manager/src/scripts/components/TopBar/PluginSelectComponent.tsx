import * as React from 'react';
import * as Radium from 'radium';
import * as renderIf from 'render-if';
import { Component, compose, pure, setDisplayName } from 'recompose';
import Select from 'react-select';
import * as _ from 'lodash';
import { CSSProperties } from 'react';
import Parser from '../utils/Parser';
import { historyPush } from '../../history';
import { pageEnum } from './NavigationComponent';
import styles from './PluginSelectComponent.styles';

const customStyles = {
  control: (base, state) => ({
    ...base,
    ...styles.control,
  }),
  indicatorsContainer: (base, state) => ({
    ...styles.indicatorsContainer,
  }),
  input: (base, state) => ({
    ...base,
    ...styles.input,
  }),
  valueContainer: (base, state) => ({
    ...base,
    ...styles.valueContainer,
  }),
  option: (base, state) => ({
    ...base,
    ...styles.option,
  }),
  singleValue: (base, state) => ({
    ...base,
    ...styles.singleValue,
  }),
};

const pluginResourceOptions = [
  'Regular dictionaries',
  'Behavior rules',
  'Output sets',
  'HTTP calls',
];

interface IOption {
  value: number;
  label: string;
}
interface IState {
  options: IOption[];
  selectedOption: IOption;
}
interface IProps {
  page: pageEnum;
}

class PluginSelectComponent extends React.Component<IProps, IState> {
  constructor(props) {
    super(props);
    this.state = {
      selectedOption: null,
      options: [],
    };
  }

  componentDidMount() {
    this.setOption();
  }

  componentWillReceiveProps(nextProps) {
    this.setOption(nextProps);
  }

  isPluginPage(props = this.props): boolean {
    return [
      pageEnum.dictionary,
      pageEnum.behavior,
      pageEnum.output,
      pageEnum.httpCalls,
    ].includes(props.page);
  }

  setOption(props = this.props) {
    if (this.isPluginPage(props)) {
      this.setState({
        selectedOption: {
          value: props.page,
          label: pluginResourceOptions[props.page],
        },
      });
    } else {
      this.setState({ selectedOption: { value: -1, label: 'Resources' } });
    }
  }

  handleSelect = (option: IOption) => {
    if (!_.isEmpty(option)) {
      this.setState({ selectedOption: option });
      historyPush('/extensions', [`type=${pageEnum[option.value]}`]);
    }
  };

  getStyles() {
    if (this.isPluginPage()) {
      return {
        ...customStyles,
        control: (base, state) => ({
          ...base,
          ...styles.control,
          ...styles.controlSelected,
        }),
        singleValue: (base, state) => ({
          ...base,
          ...styles.singleValue,
          ...styles.singleValueSelected,
        }),
      };
    } else {
      return {
        ...customStyles,
      };
    }
  }

  render() {
    return (
      <div style={styles.selectContainer}>
        <Select
          styles={this.getStyles()}
          value={this.state.selectedOption}
          options={pluginResourceOptions.map((pluginType, i) => {
            return { value: i, label: pluginType };
          })}
          onChange={this.handleSelect}
          isSearchable={false}
        />
      </div>
    );
  }
}

const ComposedPluginSelectComponent: Component<IProps> = compose<IProps>(
  pure,
  setDisplayName('PluginSelectComponent'),
)(PluginSelectComponent);

export default ComposedPluginSelectComponent;
